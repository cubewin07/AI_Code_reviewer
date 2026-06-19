package com.ai.reviewer.service;

import com.ai.reviewer.agent.JobContext;
import com.ai.reviewer.agent.ReviewAgent;
import com.ai.reviewer.agent.ReviewFormatter;
import com.ai.reviewer.agent.ReviewResult;
import com.ai.reviewer.config.AppConfig;
import com.ai.reviewer.model.Job;
import com.ai.reviewer.repository.JobRepository;
import com.ai.reviewer.client.GitHubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ai.reviewer.dto.github.GitHubComment;
import java.time.LocalDateTime;
import java.util.List;
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
    private final GitHubClient gitHubClient;

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

        ReviewResult result = null;
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
            result = reviewAgent.review(context);

            // 3. Persist result based on success
            if (result.success()) {
                String reviewMarkdown = reviewFormatter.format(result);

                // Prepend header
                String modelName = appConfig.nineRouter() != null ? appConfig.nineRouter().model() : "unknown-model";
                String header = "🤖 AI Code Review — " + modelName + " via 9Router\n\n";
                String commentBody = header + reviewMarkdown;

                // Post/Update comment on GitHub
                postOrUpdateGitHubComment(context, commentBody);

                job.setStatus("COMPLETED");
                job.setReviewText(reviewMarkdown);
                job.setError(null);
                job.setUpdatedAt(LocalDateTime.now());
                jobRepository.save(job);
                log.info("Successfully completed review and posted comment for job {}", jobId);
            } else {
                String errorMsg = result.summary() != null ? result.summary() : "Review agent returned unsuccessful status";
                handleFailure(job, new RuntimeException(errorMsg), result);
            }

        } catch (Throwable t) {
            log.error("Exception occurred while processing job {}", jobId, t);
            handleFailure(job, t, result);
        } finally {
            MDC.remove("jobId");
            MDC.remove("repo");
            MDC.remove("eventType");
        }
    }

    private void postOrUpdateGitHubComment(JobContext context, String commentBody) {
        String owner = context.getOwner();
        String repo = context.getRepo();
        long instId = context.installationId();

        if ("pull_request".equalsIgnoreCase(context.eventType())) {
            if (context.prNumber() == null) {
                log.warn("PR number is null for pull_request event, cannot post comment");
                return;
            }
            int prNum = context.prNumber();
            log.info("Checking for existing bot comments on PR {}/{} #{}", owner, repo, prNum);
            List<GitHubComment> comments =
                    gitHubClient.getPullRequestComments(owner, repo, prNum, instId);

            Optional<GitHubComment> existingBotComment = comments.stream()
                    .filter(c -> c.body() != null && c.body().contains("🤖 AI Code Review"))
                    .findFirst();

            if (existingBotComment.isPresent()) {
                log.info("Updating existing bot comment ID {} on PR {}/{} #{}", existingBotComment.get().id(), owner, repo, prNum);
                gitHubClient.updatePullRequestComment(owner, repo, existingBotComment.get().id(), commentBody, instId);
            } else {
                log.info("Creating new bot comment on PR {}/{} #{}", owner, repo, prNum);
                gitHubClient.postPullRequestComment(owner, repo, prNum, commentBody, instId);
            }
        } else if ("push".equalsIgnoreCase(context.eventType())) {
            if (context.commitSha() == null) {
                log.warn("Commit SHA is null for push event, cannot post comment");
                return;
            }
            String sha = context.commitSha();
            log.info("Checking for existing bot comments on commit {}/{} @{}", owner, repo, sha);
            List<GitHubComment> comments =
                    gitHubClient.getCommitComments(owner, repo, sha, instId);

            Optional<GitHubComment> existingBotComment = comments.stream()
                    .filter(c -> c.body() != null && c.body().contains("🤖 AI Code Review"))
                    .findFirst();

            if (existingBotComment.isPresent()) {
                log.info("Updating existing bot comment ID {} on commit {}/{} @{}", existingBotComment.get().id(), owner, repo, sha);
                gitHubClient.updateCommitComment(owner, repo, existingBotComment.get().id(), commentBody, instId);
            } else {
                log.info("Creating new bot comment on commit {}/{} @{}", owner, repo, sha);
                gitHubClient.postCommitComment(owner, repo, sha, commentBody, instId);
            }
        } else {
            log.warn("Unsupported event type for comment posting: {}", context.eventType());
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
