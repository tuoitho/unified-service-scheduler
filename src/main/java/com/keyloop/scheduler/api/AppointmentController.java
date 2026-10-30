package com.keyloop.scheduler.api;

import com.keyloop.scheduler.api.dto.AppointmentResponse;
import com.keyloop.scheduler.api.dto.BookAppointmentRequest;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.service.AppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
@Tag(name = "Appointments", description = "Resource-constrained service appointment booking and lifecycle management")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    @Operation(
            summary = "Book a service appointment",
            description = "Atomically verifies and allocates both an available ServiceBay and a qualified Technician "
                    + "for the full service duration before confirming the appointment."
    )
    @ApiResponse(responseCode = "201", description = "Appointment confirmed and resources allocated")
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @ApiResponse(responseCode = "404", description = "Dealership or ServiceType not found")
    @ApiResponse(responseCode = "409", description = "No available bay/qualified technician, or vehicle already booked")
    @ApiResponse(responseCode = "422", description = "Requested time is in the past or outside operating hours")
    public ResponseEntity<AppointmentResponse> bookAppointment(
            @Valid @RequestBody BookAppointmentRequest request
    ) {
        AppointmentResponse confirmed = appointmentService.bookAppointment(request);
        URI location = URI.create("/api/v1/appointments/" + confirmed.id());
        return ResponseEntity.created(location).body(confirmed);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get appointment by ID")
    @ApiResponse(responseCode = "200", description = "Appointment details returned")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    public AppointmentResponse getAppointment(@PathVariable UUID id) {
        return appointmentService.getAppointment(id);
    }

    @GetMapping
    @Operation(summary = "List and filter appointments")
    public List<AppointmentResponse> listAppointments(
            @RequestParam(required = false) UUID dealershipId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID vehicleId
    ) {
        return appointmentService.listAppointments(dealershipId, date, status, customerId, vehicleId);
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel an appointment",
            description = "Transitions an appointment to CANCELLED and immediately releases its bay and technician."
    )
    @ApiResponse(responseCode = "200", description = "Appointment cancelled")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    public AppointmentResponse cancelAppointment(@PathVariable UUID id) {
        return appointmentService.cancelAppointment(id);
    }
}
