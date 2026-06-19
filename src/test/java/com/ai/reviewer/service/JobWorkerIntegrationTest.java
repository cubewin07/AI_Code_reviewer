package com.ai.reviewer.service;

import com.ai.reviewer.agent.ReviewAgent;
import com.ai.reviewer.agent.ReviewResult;
import com.ai.reviewer.agent.ReviewIssue;
import com.ai.reviewer.model.Job;
import com.ai.reviewer.repository.JobRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "app.worker.max-attempts=3",
    "app.worker.poll-delay-ms=60000" // Slow down the default scheduler trigger
})
@ActiveProfiles("test")
class JobWorkerIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobScheduler jobScheduler;

    @Autowired
    private GitHubAuthService authService;

    @MockitoBean
    private ReviewAgent reviewAgent;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @org.springframework.test.context.DynamicPropertySource
    static void registerProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.github.api-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        wireMockServer.resetAll();
        authService.clearCache();
    }

    @Test
    void testSuccessfulJobLifecycle() throws Exception {
        // Stub access token request
        stubFor(post(urlEqualTo("/app/installations/0/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        // Stub GET comments for push commit -> empty comments list
        stubFor(get(urlEqualTo("/repos/owner/repo/commits/1111/comments"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        // Stub POST comment for push commit
        stubFor(post(urlEqualTo("/repos/owner/repo/commits/1111/comments"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"body\":\"🤖 AI Code Review — meta-llama/llama-3-70b-instruct via 9Router\\n\\nCompleted code review.\"}")));

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

        // Verify that the comment was posted to GitHub with the badge/header prefix
        verify(postRequestedFor(urlEqualTo("/repos/owner/repo/commits/1111/comments"))
                .withRequestBody(matchingJsonPath("$.body", containing("🤖 AI Code Review — meta-llama/llama-3-70b-instruct via 9Router"))));
    }

    @Test
    void testSuccessfulPullRequestJobLifecycleWithDeduplication() throws Exception {
        // Stub access token request
        stubFor(post(urlEqualTo("/app/installations/0/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        // Stub GET comments for PR -> already has a bot comment with id 999
        stubFor(get(urlEqualTo("/repos/owner/repo/issues/1/comments"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":999,\"body\":\"🤖 AI Code Review — some review\"}]")));

        // Stub PATCH comment for PR issue comment 999
        stubFor(patch(urlEqualTo("/repos/owner/repo/issues/comments/999"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":999,\"body\":\"🤖 AI Code Review — meta-llama/llama-3-70b-instruct via 9Router\\n\\nUpdated review.\"}")));

        ReviewResult successResult = new ReviewResult(
                List.of(new ReviewIssue("App.java", 1, "Warning", "Style issue")),
                "Updated review.",
                true,
                null,
                1
        );
        when(reviewAgent.review(any())).thenReturn(successResult);

        Job job = Job.builder()
                .eventType("pull_request")
                .repoFullName("owner/repo")
                .deliveryId("del-333")
                .payload("{\"number\":1,\"pull_request\":{\"number\":1,\"head\":{\"sha\":\"123\"}},\"repository\":{\"full_name\":\"owner/repo\"}}")
                .status("PENDING")
                .attempts(0)
                .build();
        job = jobRepository.save(job);

        jobScheduler.pollAndProcessJobs();

        waitForJobStatus(job.getId(), "COMPLETED");

        Job finishedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finishedJob.getStatus()).isEqualTo("COMPLETED");

        // Verify that PATCH was called (deduplication) and POST was not called
        verify(patchRequestedFor(urlEqualTo("/repos/owner/repo/issues/comments/999"))
                .withRequestBody(matchingJsonPath("$.body", containing("🤖 AI Code Review —"))));
        verify(0, postRequestedFor(urlEqualTo("/repos/owner/repo/issues/1/comments")));
    }

    @Test
    void testJobFailureWhenCommentPostFails() throws Exception {
        // Stub access token request
        stubFor(post(urlEqualTo("/app/installations/0/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        // Stub GET comments for push commit -> empty comments list
        stubFor(get(urlEqualTo("/repos/owner/repo/commits/1111/comments"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        // Stub POST comment for push commit to FAIL with 500 error
        stubFor(post(urlEqualTo("/repos/owner/repo/commits/1111/comments"))
                .willReturn(aResponse()
                        .withStatus(500)));

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
                .deliveryId("del-444")
                .payload("{\"ref\":\"refs/heads/main\",\"before\":\"0000\",\"after\":\"1111\",\"repository\":{\"full_name\":\"owner/repo\"}}")
                .status("PENDING")
                .attempts(0)
                .build();
        job = jobRepository.save(job);

        jobScheduler.pollAndProcessJobs();

        waitForJobStatus(job.getId(), "FAILED");

        Job failedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo("FAILED");
        assertThat(failedJob.getAttempts()).isEqualTo(1);
        assertThat(failedJob.getError()).contains("Failed to post commit comment");
        // Review text should still be saved in DB
        assertThat(failedJob.getReviewText()).contains("Null pointer risk");
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
