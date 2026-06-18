package com.ai.reviewer.service;

import com.ai.reviewer.agent.ReviewAgent;
import com.ai.reviewer.agent.ReviewResult;
import com.ai.reviewer.agent.ReviewIssue;
import com.ai.reviewer.model.Job;
import com.ai.reviewer.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "app.worker.max-attempts=3",
    "app.worker.poll-delay-ms=60000" // Slow down the default scheduler trigger
})
@ActiveProfiles("test")
class JobWorkerIntegrationTest {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobScheduler jobScheduler;

    @MockitoBean
    private ReviewAgent reviewAgent;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
    }

    @Test
    void testSuccessfulJobLifecycle() throws Exception {
        ReviewResult successResult = new ReviewResult(
                List.of(new ReviewIssue("Main.java", 12, "Error", "Null pointer risk")),
                "Completed code review.",
                true,
                null,
                1
        );
        when(reviewAgent.review(any())).thenReturn(successResult);

        Job job = Job.builder()
                .eventType("push")
                .repoFullName("owner/repo")
                .deliveryId("del-111")
                .payload("{\"ref\":\"refs/heads/main\",\"before\":\"0000\",\"after\":\"1111\",\"repository\":{\"full_name\":\"owner/repo\"}}")
                .status("PENDING")
                .attempts(0)
                .build();
        job = jobRepository.save(job);

        jobScheduler.pollAndProcessJobs();

        waitForJobStatus(job.getId(), "COMPLETED");

        Job finishedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finishedJob.getStatus()).isEqualTo("COMPLETED");
        assertThat(finishedJob.getReviewText()).contains("Null pointer risk");
        assertThat(finishedJob.getError()).isNull();
        assertThat(finishedJob.getAttempts()).isEqualTo(0);
    }

    @Test
    void testJobFailureAndRetryToDeadLetter() throws Exception {
        ReviewResult failResult = new ReviewResult(
                List.of(),
                "API rate limit exceeded",
                false,
                "Partial process result",
                1
        );
        when(reviewAgent.review(any())).thenReturn(failResult);

        Job job = Job.builder()
                .eventType("pull_request")
                .repoFullName("owner/repo")
                .deliveryId("del-222")
                .payload("{\"number\":1,\"pull_request\":{\"number\":1,\"head\":{\"sha\":\"123\"}},\"repository\":{\"full_name\":\"owner/repo\"}}")
                .status("PENDING")
                .attempts(0)
                .build();
        job = jobRepository.save(job);

        // First attempt: PENDING -> IN_PROGRESS -> FAILED
        jobScheduler.pollAndProcessJobs();
        waitForJobStatus(job.getId(), "FAILED");

        Job attempt1 = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(attempt1.getStatus()).isEqualTo("FAILED");
        assertThat(attempt1.getAttempts()).isEqualTo(1);
        assertThat(attempt1.getError()).isEqualTo("API rate limit exceeded");

        // Second attempt: FAILED -> IN_PROGRESS -> FAILED
        jobScheduler.pollAndProcessJobs();
        waitForJobStatus(job.getId(), "FAILED", 2);

        Job attempt2 = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(attempt2.getStatus()).isEqualTo("FAILED");
        assertThat(attempt2.getAttempts()).isEqualTo(2);

        // Third attempt: FAILED -> IN_PROGRESS -> DEAD_LETTER
        jobScheduler.pollAndProcessJobs();
        waitForJobStatus(job.getId(), "DEAD_LETTER");

        Job attempt3 = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(attempt3.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(attempt3.getAttempts()).isEqualTo(3);
    }

    private void waitForJobStatus(Long jobId, String targetStatus) throws InterruptedException {
        waitForJobStatus(jobId, targetStatus, -1);
    }

    private void waitForJobStatus(Long jobId, String targetStatus, int expectedAttempts) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job != null && targetStatus.equals(job.getStatus())) {
                if (expectedAttempts == -1 || job.getAttempts() == expectedAttempts) {
                    return;
                }
            }
            Thread.sleep(50);
        }
    }
}
