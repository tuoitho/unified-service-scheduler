package com.keyloop.scheduler.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.exception.BusinessValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimeWindowValidatorTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    private final TimeWindowValidator validator = new TimeWindowValidator(fixedClock);

    @Test
    void acceptsAppointmentsExactlyAtOpenAndClosingBoundariesInUtc() {
        Dealership dealership = new Dealership(
                UUID.randomUUID(), "KL-UTC", "UTC Site", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThatCode(() -> validator.validateBookingWindow(
                dealership,
                OffsetDateTime.parse("2026-11-10T08:00:00Z"),
                OffsetDateTime.parse("2026-11-10T09:00:00Z")))
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validateBookingWindow(
                dealership,
                OffsetDateTime.parse("2026-11-10T17:00:00Z"),
                OffsetDateTime.parse("2026-11-10T18:00:00Z")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsStartTimeInThePast() {
        Dealership dealership = new Dealership(
                UUID.randomUUID(), "KL-UTC", "UTC Site", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThatThrownBy(() -> validator.validateBookingWindow(
                dealership,
                OffsetDateTime.parse("2026-11-01T09:59:00Z"),
                OffsetDateTime.parse("2026-11-01T10:59:00Z")))
                .isInstanceOf(BusinessValidationException.class)
                .extracting("errorCode")
                .isEqualTo("PAST_START_TIME");
    }

    @Test
    void rejectsAppointmentsBeforeOpenOrExceedingCloseOrSpanningOvernight() {
        Dealership dealership = new Dealership(
                UUID.randomUUID(), "KL-UTC", "UTC Site", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThatThrownBy(() -> validator.validateBookingWindow(
                dealership,
                OffsetDateTime.parse("2026-11-10T07:30:00Z"),
                OffsetDateTime.parse("2026-11-10T08:30:00Z")))
                .isInstanceOf(BusinessValidationException.class)
                .extracting("errorCode")
                .isEqualTo("OUTSIDE_OPERATING_HOURS");

        assertThatThrownBy(() -> validator.validateBookingWindow(
                dealership,
                OffsetDateTime.parse("2026-11-10T17:01:00Z"),
                OffsetDateTime.parse("2026-11-10T18:01:00Z")))
                .isInstanceOf(BusinessValidationException.class)
                .extracting("errorCode")
                .isEqualTo("OUTSIDE_OPERATING_HOURS");

        assertThatThrownBy(() -> validator.validateBookingWindow(
                dealership,
                OffsetDateTime.parse("2026-11-10T23:00:00Z"),
                OffsetDateTime.parse("2026-11-11T01:00:00Z")))
                .isInstanceOf(BusinessValidationException.class)
                .extracting("errorCode")
                .isEqualTo("OUTSIDE_OPERATING_HOURS");
    }

    @Test
    void evaluatesOperatingHoursInDealershipLocalTimezoneDuringBritishSummerTime() {
        // July in Europe/London is BST (UTC+1): 08:00 BST == 07:00 UTC
        Dealership londonDealership = new Dealership(
                UUID.randomUUID(), "KL-LON", "London Site", "Europe/London",
                LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThatCode(() -> validator.validateBookingWindow(
                londonDealership,
                OffsetDateTime.parse("2027-07-15T07:00:00Z"),
                OffsetDateTime.parse("2027-07-15T08:00:00Z")))
                .doesNotThrowAnyException();

        // 17:30 UTC in July is 18:30 BST -> exceeds 18:00 BST close
        assertThatThrownBy(() -> validator.validateBookingWindow(
                londonDealership,
                OffsetDateTime.parse("2027-07-15T16:30:00Z"),
                OffsetDateTime.parse("2027-07-15T17:30:00Z")))
                .isInstanceOf(BusinessValidationException.class)
                .extracting("errorCode")
                .isEqualTo("OUTSIDE_OPERATING_HOURS");
    }
}
