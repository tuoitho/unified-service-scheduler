package com.keyloop.scheduler.service;

import com.keyloop.scheduler.api.dto.AppointmentResponse;
import com.keyloop.scheduler.api.dto.BookAppointmentRequest;
import com.keyloop.scheduler.domain.Appointment;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.ServiceType;
import com.keyloop.scheduler.domain.Technician;
import com.keyloop.scheduler.exception.BusinessValidationException;
import com.keyloop.scheduler.exception.ResourceNotFoundException;
import com.keyloop.scheduler.exception.SchedulingConflictException;
import com.keyloop.scheduler.observability.SchedulerMetrics;
import com.keyloop.scheduler.repository.AppointmentRepository;
import com.keyloop.scheduler.repository.DealershipRepository;
import com.keyloop.scheduler.repository.ServiceTypeRepository;
import io.micrometer.core.instrument.Timer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);
    private static final String PSQL_EXCLUSION_VIOLATION_SQLSTATE = "23P01";

    private final DealershipRepository dealershipRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final AppointmentRepository appointmentRepository;
    private final TimeWindowValidator timeWindowValidator;
    private final SchedulerMetrics schedulerMetrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AppointmentService(
            DealershipRepository dealershipRepository,
            ServiceTypeRepository serviceTypeRepository,
            AppointmentRepository appointmentRepository,
            TimeWindowValidator timeWindowValidator,
            SchedulerMetrics schedulerMetrics,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.dealershipRepository = dealershipRepository;
        this.serviceTypeRepository = serviceTypeRepository;
        this.appointmentRepository = appointmentRepository;
        this.timeWindowValidator = timeWindowValidator;
        this.schedulerMetrics = schedulerMetrics;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public AppointmentResponse bookAppointment(BookAppointmentRequest request) {
        Timer.Sample timerSample = schedulerMetrics.startBookingTimer();
        String outcome = "error";
        try {
            AppointmentResponse response = transactionTemplate.execute(status -> executeBookingInTransaction(request));
            outcome = "confirmed";
            return response;
        } catch (SchedulingConflictException ex) {
            outcome = mapConflictOutcomeTag(ex.getErrorCode());
            log.warn("Booking conflict errorCode={} dealershipId={} desiredStartTime={}",
                    ex.getErrorCode(), request.dealershipId(), request.desiredStartTime());
            throw ex;
        } catch (BusinessValidationException ex) {
            outcome = "validation_error";
            throw ex;
        } catch (ResourceNotFoundException ex) {
            outcome = "not_found";
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            if (isExclusionConstraintViolation(ex)) {
                outcome = "conflict_constraint";
                log.warn("Database exclusion constraint prevented overlapping booking dealershipId={} desiredStartTime={}",
                        request.dealershipId(), request.desiredStartTime());
                throw new SchedulingConflictException(
                        "RESOURCE_CONFLICT",
                        "Requested time window is no longer available due to a concurrent booking",
                        ex);
            }
            throw ex;
        } finally {
            schedulerMetrics.recordBookingOutcome(outcome, request.dealershipId());
            schedulerMetrics.stopBookingTimer(timerSample, outcome);
        }
    }

    private AppointmentResponse executeBookingInTransaction(BookAppointmentRequest request) {
        appointmentRepository.configureBookingLockTimeout();

        Dealership dealership = dealershipRepository.findById(request.dealershipId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DEALERSHIP_NOT_FOUND",
                        "Dealership not found: " + request.dealershipId()));

        ServiceType serviceType = serviceTypeRepository.findById(request.serviceTypeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SERVICE_TYPE_NOT_FOUND",
                        "Service type not found: " + request.serviceTypeId()));

        OffsetDateTime startTimeUtc = request.desiredStartTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime endTimeUtc = startTimeUtc.plusMinutes(serviceType.getDurationMinutes());

        timeWindowValidator.validateBookingWindow(dealership, startTimeUtc, endTimeUtc);

        // Lock Hierarchy Step 1: Transaction-scoped advisory lock on vehicleId + overlap check
        if (appointmentRepository.lockVehicleAndHasOverlap(request.vehicleId(), startTimeUtc, endTimeUtc)) {
            throw new SchedulingConflictException(
                    "VEHICLE_ALREADY_BOOKED",
                    "Vehicle already has a confirmed overlapping appointment during the requested window");
        }

        // Lock Hierarchy Step 2: Acquire available active ServiceBay
        ServiceBay allocatedBay = appointmentRepository
                .acquireAvailableServiceBay(dealership.getId(), startTimeUtc, endTimeUtc)
                .orElseThrow(() -> new SchedulingConflictException(
                        "NO_SERVICE_BAY_AVAILABLE",
                        "No service bay is continuously available at the dealership for the requested time window"));

        // Lock Hierarchy Step 3: Acquire least-specialized available qualified Technician
        String requiredQual = serviceType.getRequiredQualification();
        Technician allocatedTechnician = appointmentRepository
                .acquireAvailableQualifiedTechnician(dealership.getId(), requiredQual, startTimeUtc, endTimeUtc)
                .orElseThrow(() -> new SchedulingConflictException(
                        "NO_QUALIFIED_TECHNICIAN_AVAILABLE",
                        String.format("No technician holding qualification '%s' is available for the requested time window",
                                requiredQual)));

        OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        Appointment appointment = new Appointment(
                UUID.randomUUID(),
                dealership,
                request.customerId(),
                request.vehicleId(),
                serviceType,
                allocatedBay,
                allocatedTechnician,
                startTimeUtc,
                endTimeUtc,
                AppointmentStatus.CONFIRMED,
                request.notes(),
                nowUtc);

        // Force immediate SQL INSERT inside the transaction so GiST constraints validate now
        appointmentRepository.saveAndFlush(appointment);

        log.info("Confirmed appointment id={} dealershipCode={} serviceTypeCode={} bay={} technician={} start={} end={}",
                appointment.getId(),
                dealership.getCode(),
                serviceType.getCode(),
                allocatedBay.getBayNumber(),
                allocatedTechnician.getEmployeeCode(),
                startTimeUtc,
                endTimeUtc);

        return AppointmentResponse.from(appointment);
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getAppointment(UUID id) {
        Appointment appointment = appointmentRepository.findDetailedById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "APPOINTMENT_NOT_FOUND",
                        "Appointment not found: " + id));
        return AppointmentResponse.from(appointment);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> listAppointments(
            UUID dealershipId,
            LocalDate date,
            AppointmentStatus status,
            UUID customerId,
            UUID vehicleId
    ) {
        OffsetDateTime windowStart = null;
        OffsetDateTime windowEnd = null;

        if (date != null) {
            ZoneId zoneId = ZoneOffset.UTC;
            if (dealershipId != null) {
                Dealership dealership = dealershipRepository.findById(dealershipId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "DEALERSHIP_NOT_FOUND",
                                "Dealership not found: " + dealershipId));
                zoneId = ZoneId.of(dealership.getTimezone());
            }
            ZonedDateTime startOfDay = date.atStartOfDay(zoneId);
            windowStart = startOfDay.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            windowEnd = startOfDay.plusDays(1).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        }

        return appointmentRepository
                .findDetailedByFilters(dealershipId, windowStart, windowEnd, status, customerId, vehicleId)
                .stream()
                .map(AppointmentResponse::from)
                .toList();
    }

    @Transactional
    public AppointmentResponse cancelAppointment(UUID id) {
        Appointment appointment = appointmentRepository.findDetailedById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "APPOINTMENT_NOT_FOUND",
                        "Appointment not found: " + id));

        OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (appointment.cancel(nowUtc)) {
            appointmentRepository.flush();
            log.info("Cancelled appointment id={} dealershipCode={}", id, appointment.getDealership().getCode());
        }
        return AppointmentResponse.from(appointment);
    }

    private String mapConflictOutcomeTag(String errorCode) {
        return switch (errorCode) {
            case "NO_SERVICE_BAY_AVAILABLE" -> "conflict_bay";
            case "NO_QUALIFIED_TECHNICIAN_AVAILABLE" -> "conflict_technician";
            case "VEHICLE_ALREADY_BOOKED" -> "conflict_vehicle";
            default -> "conflict_other";
        };
    }

    private boolean isExclusionConstraintViolation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof SQLException sqlEx
                    && PSQL_EXCLUSION_VIOLATION_SQLSTATE.equals(sqlEx.getSQLState())) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
