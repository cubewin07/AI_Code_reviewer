package com.ai.reviewer.service;

import com.ai.reviewer.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JobMetricsExporter {

    private final JobRepository jobRepository;
    private final MeterRegistry meterRegistry;

    private static final List<String> STATUSES = List.of(
            "PENDING",
            "IN_PROGRESS",
            "COMPLETED",
            "FAILED",
            "DEAD_LETTER"
    );

    @PostConstruct
    public void init() {
        for (String status : STATUSES) {
            final String finalStatus = status;
            Gauge.builder("jobs.database.count", jobRepository, repo -> repo.countByStatus(finalStatus))
                    .tag("status", finalStatus)
                    .description("Current number of jobs in database with status " + finalStatus)
                    .register(meterRegistry);
        }
    }
}
