package com.keyloop.scheduler.service;

import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.exception.BusinessValidationException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

@Component
public class TimeWindowValidator {

    private final Clock clock;

    public TimeWindowValidator(Clock clock) {
        this.clock = clock;
    }

    public void validateBookingWindow(Dealership dealership, OffsetDateTime startTimeUtc, OffsetDateTime endTimeUtc) {
        OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (startTimeUtc.isBefore(nowUtc)) {
            throw new BusinessValidationException(
                    "PAST_START_TIME",
                    "Desired start time must not be in the past");
        }
        if (!isWithinOperatingHours(dealership, startTimeUtc, endTimeUtc)) {
            throw new BusinessValidationException(
                    "OUTSIDE_OPERATING_HOURS",
                    String.format(
                            "Requested service window [%s, %s) falls outside dealership operating hours (%s-%s %s)",
                            startTimeUtc,
                            endTimeUtc,
                            dealership.getOpenTime(),
                            dealership.getCloseTime(),
                            dealership.getTimezone()));
        }
    }

    public boolean isWithinOperatingHours(
            Dealership dealership,
            OffsetDateTime startTimeUtc,
            OffsetDateTime endTimeUtc
    ) {
        if (!endTimeUtc.isAfter(startTimeUtc)) {
            return false;
        }
        ZoneId zoneId = ZoneId.of(dealership.getTimezone());
        ZonedDateTime localStart = startTimeUtc.atZoneSameInstant(zoneId);
        ZonedDateTime localEnd = endTimeUtc.atZoneSameInstant(zoneId);

        ZonedDateTime dayOpen = ZonedDateTime.of(localStart.toLocalDate(), dealership.getOpenTime(), zoneId);
        ZonedDateTime dayClose = ZonedDateTime.of(localStart.toLocalDate(), dealership.getCloseTime(), zoneId);

        return !localStart.isBefore(dayOpen) && !localEnd.isAfter(dayClose);
    }

    public boolean isInPast(OffsetDateTime timestampUtc) {
        OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        return timestampUtc.isBefore(nowUtc);
    }
}
