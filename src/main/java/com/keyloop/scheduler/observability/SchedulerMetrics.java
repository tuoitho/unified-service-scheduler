package com.keyloop.scheduler.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SchedulerMetrics {

    private static final String REQUESTS_METRIC = "scheduler_booking_requests_total";
    private static final String DURATION_METRIC = "scheduler.booking.duration";

    private final MeterRegistry meterRegistry;

    public SchedulerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startBookingTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopBookingTimer(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(DURATION_METRIC)
                .description("End-to-end transactional appointment booking latency")
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    public void recordBookingOutcome(String outcome, UUID dealershipId) {
        Counter.builder(REQUESTS_METRIC)
                .description("Total appointment booking attempts partitioned by outcome and dealership")
                .tag("outcome", outcome)
                .tag("dealership_id", dealershipId != null ? dealershipId.toString() : "unknown")
                .register(meterRegistry)
                .increment();
    }
}
