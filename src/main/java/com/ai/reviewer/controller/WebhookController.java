package com.ai.reviewer.controller;

import com.ai.reviewer.dto.github.GithubEventModels;
import com.ai.reviewer.model.Job;
import com.ai.reviewer.repository.JobRepository;
import com.ai.reviewer.service.WebhookSignatureValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final WebhookSignatureValidator signatureValidator;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody String rawBody
    ) {
        if (eventType == null || deliveryId == null) {
            log.warn("Missing required GitHub webhook headers (X-GitHub-Event or X-GitHub-Delivery)");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing required headers");
        }

        try {
            MDC.put("eventType", eventType);

            // 1. Validate HMAC-SHA256 signature
            if (!signatureValidator.isValid(signature, rawBody)) {
                log.warn("Webhook signature validation failed for delivery: {}", deliveryId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
            }

            // 2. Handle ping event as no-op
            if ("ping".equalsIgnoreCase(eventType)) {
                log.info("Received ping event from GitHub webhook. Connection active.");
                return ResponseEntity.ok("Ping received successfully");
            }

            // 3. Reject/ignore unsupported events (only push and pull_request are processed)
            if (!"push".equalsIgnoreCase(eventType) && !"pull_request".equalsIgnoreCase(eventType)) {
                log.info("Received unsupported event type: {}. Ignoring.", eventType);
                return ResponseEntity.ok("Unsupported event type ignored");
            }

            // 4. Parse payload to extract repository full name
            String repoFullName;
            try {
                if ("push".equalsIgnoreCase(eventType)) {
                    GithubEventModels.PushEvent pushEvent = objectMapper.readValue(rawBody, GithubEventModels.PushEvent.class);
                    repoFullName = (pushEvent.repository() != null) ? pushEvent.repository().fullName() : null;
                } else {
                    GithubEventModels.PullRequestEvent prEvent = objectMapper.readValue(rawBody, GithubEventModels.PullRequestEvent.class);
                    repoFullName = (prEvent.repository() != null) ? prEvent.repository().fullName() : null;
                }
            } catch (Exception e) {
                log.error("Failed to parse event body into typed POJO. Event: {}, Delivery ID: {}", eventType, deliveryId, e);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Failed to parse event body");
            }

            if (repoFullName == null || repoFullName.isBlank()) {
                log.warn("Parsed event repository full_name is empty. Event: {}, Delivery ID: {}", eventType, deliveryId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Repository full name cannot be blank");
            }

            MDC.put("repo", repoFullName);

            // 5. Check idempotency: ignore duplicate deliveries
            if (jobRepository.existsByRepoFullNameAndEventTypeAndDeliveryId(repoFullName, eventType, deliveryId)) {
                log.info("Duplicate webhook delivery detected (exists in database). Event: {}, Delivery ID: {}. Ignoring.", eventType, deliveryId);
                return ResponseEntity.ok("Duplicate delivery ignored");
            }

            // 6. Enqueue the job as PENDING
            Job job = Job.builder()
                    .eventType(eventType)
                    .repoFullName(repoFullName)
                    .deliveryId(deliveryId)
                    .payload(rawBody)
                    .status("PENDING")
                    .attempts(0)
                    .build();

            try {
                job = jobRepository.save(job);
                MDC.put("jobId", String.valueOf(job.getId()));
                log.info("Successfully enqueued job. Job ID: {}, Event: {}, Repo: {}, Delivery ID: {}",
                        job.getId(), eventType, repoFullName, deliveryId);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body("Job enqueued");
            } catch (DataIntegrityViolationException dive) {
                log.info("Duplicate webhook delivery detected via database unique constraint. Event: {}, Delivery ID: {}. Ignoring.", eventType, deliveryId);
                return ResponseEntity.ok("Duplicate delivery ignored");
            }

        } finally {
            MDC.remove("eventType");
            MDC.remove("repo");
            MDC.remove("jobId");
        }
    }
}
