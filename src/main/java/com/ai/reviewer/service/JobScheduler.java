package com.ai.reviewer.service;

import com.ai.reviewer.config.AppConfig;
import com.ai.reviewer.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobScheduler {

    private final JobRepository jobRepository;
    private final JobProcessor jobProcessor;
    private final AppConfig appConfig;

    @Scheduled(fixedDelayString = "${app.worker.poll-delay-ms:5000}")
    public void pollAndProcessJobs() {
        int maxAttempts = appConfig.worker() != null ? appConfig.worker().maxAttempts() : 3;
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }

        List<Long> candidateJobIds = jobRepository.findCandidateJobIds(maxAttempts);
        if (candidateJobIds.isEmpty()) {
            return;
        }

        log.debug("Found {} candidate jobs to process", candidateJobIds.size());

        for (Long jobId : candidateJobIds) {
            try {
                // Atomic transition from PENDING/FAILED to IN_PROGRESS
                int updated = jobRepository.acquireJob(jobId, "IN_PROGRESS", LocalDateTime.now());
                if (updated > 0) {
                    log.info("Successfully acquired job {}. Triggering async execution.", jobId);
                    jobProcessor.processJobAsync(jobId);
                } else {
                    log.debug("Job {} was already acquired by another worker.", jobId);
                }
            } catch (Exception e) {
                log.error("Failed to acquire or initiate processing for job {}", jobId, e);
            }
        }
    }
}
