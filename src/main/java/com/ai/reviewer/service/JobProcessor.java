package com.ai.reviewer.service;

import com.ai.reviewer.agent.JobContext;
import com.ai.reviewer.agent.ReviewAgent;
import com.ai.reviewer.agent.ReviewFormatter;
import com.ai.reviewer.agent.ReviewResult;
import com.ai.reviewer.config.AppConfig;
import com.ai.reviewer.model.Job;
import com.ai.reviewer.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobProcessor {

    private final JobRepository jobRepository;
    private final ReviewAgent reviewAgent;
    private final ReviewFormatter reviewFormatter;
    private final ObjectMapper objectMapper;
    private final AppConfig appConfig;

    @Async("jobExecutor")
    @Transactional
    public void processJobAsync(Long jobId) {
        Optional<Job> jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Job not found: {}", jobId);
            return;
        }
        Job job = jobOpt.get();

        MDC.put("jobId", String.valueOf(job.getId()));
        MDC.put("repo", job.getRepoFullName());
        MDC.put("eventType", job.getEventType());

        try {
            log.info("Starting processing for job {}", jobId);

            // 1. Deserialize payload -> JobContext
            JobContext context = JobContext.fromWebhook(
                    job.getId(),
                    job.getEventType(),
                    job.getRepoFullName(),
                    job.getPayload(),
                    objectMapper
            );

            // 2. Call ReviewAgent
            ReviewResult result = reviewAgent.review(context);

            // 3. Persist result based on success
            if (result.success()) {
                job.setStatus("COMPLETED");
                job.setReviewText(reviewFormatter.format(result));
                job.setError(null);
                job.setUpdatedAt(LocalDateTime.now());
                jobRepository.save(job);
                log.info("Successfully completed review for job {}", jobId);
            } else {
                String errorMsg = result.summary() != null ? result.summary() : "Review agent returned unsuccessful status";
                handleFailure(job, new RuntimeException(errorMsg), result);
            }

        } catch (Throwable t) {
            log.error("Exception occurred while processing job {}", jobId, t);
            handleFailure(job, t, null);
        } finally {
            MDC.remove("jobId");
            MDC.remove("repo");
            MDC.remove("eventType");
        }
    }

    private void handleFailure(Job job, Throwable t, ReviewResult result) {
        int nextAttempts = job.getAttempts() + 1;
        job.setAttempts(nextAttempts);
        job.setError(t.getMessage());

        int maxAttempts = appConfig.worker() != null ? appConfig.worker().maxAttempts() : 3;
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }

        if (nextAttempts >= maxAttempts) {
            job.setStatus("DEAD_LETTER");
            log.warn("Job {} reached max attempts ({}) and is marked as DEAD_LETTER. Error: {}",
                    job.getId(), maxAttempts, t.getMessage());
        } else {
            job.setStatus("FAILED");
            log.info("Job {} failed (attempt {}/{}). Will retry. Error: {}",
                    job.getId(), nextAttempts, maxAttempts, t.getMessage());
        }

        if (result != null) {
            // If we have a partial review result formatted by the agent, save it
            job.setReviewText(reviewFormatter.format(result));
        }

        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);
    }
}
