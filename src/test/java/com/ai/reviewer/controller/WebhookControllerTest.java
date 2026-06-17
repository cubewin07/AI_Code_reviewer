package com.ai.reviewer.controller;

import com.ai.reviewer.model.Job;
import com.ai.reviewer.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
    }

    private String calculateSignature(String payload) throws Exception {
        String secret = "test-webhook-secret";
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKey);
        byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hmac);
    }

    @Test
    void testPingEventReturns200AndDoesNotEnqueue() throws Exception {
        String payload = "{\"zen\":\"Keep it simple, stupid.\"}";
        String signature = calculateSignature(payload);

        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "ping-delivery-id-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertThat(jobRepository.findAll()).isEmpty();
    }

    @Test
    void testValidPushEventEnqueuesJobAndReturns202() throws Exception {
        String payload = "{\"ref\":\"refs/heads/main\",\"before\":\"0000\",\"after\":\"1111\",\"repository\":{\"full_name\":\"test-owner/test-repo\"}}";
        String signature = calculateSignature(payload);
        String deliveryId = "push-delivery-123";

        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        List<Job> jobs = jobRepository.findAll();
        assertThat(jobs).hasSize(1);
        Job job = jobs.get(0);
        assertThat(job.getEventType()).isEqualTo("push");
        assertThat(job.getRepoFullName()).isEqualTo("test-owner/test-repo");
        assertThat(job.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getPayload()).isEqualTo(payload);
    }

    @Test
    void testValidPullRequestEventEnqueuesJobAndReturns202() throws Exception {
        String payload = "{\"action\":\"opened\",\"number\":5,\"pull_request\":{\"number\":5,\"title\":\"Test PR\",\"body\":\"PR body\"},\"repository\":{\"full_name\":\"test-owner/test-repo\"}}";
        String signature = calculateSignature(payload);
        String deliveryId = "pr-delivery-456";

        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        List<Job> jobs = jobRepository.findAll();
        assertThat(jobs).hasSize(1);
        Job job = jobs.get(0);
        assertThat(job.getEventType()).isEqualTo("pull_request");
        assertThat(job.getRepoFullName()).isEqualTo("test-owner/test-repo");
        assertThat(job.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(job.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void testInvalidSignatureReturns401() throws Exception {
        String payload = "{\"ref\":\"refs/heads/main\",\"repository\":{\"full_name\":\"test-owner/test-repo\"}}";
        String invalidSignature = "sha256=invalidhashvaluehere";

        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", invalidSignature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        assertThat(jobRepository.findAll()).isEmpty();
    }

    @Test
    void testDuplicatePushEventReturns200NoOp() throws Exception {
        String payload = "{\"ref\":\"refs/heads/main\",\"repository\":{\"full_name\":\"test-owner/test-repo\"}}";
        String signature = calculateSignature(payload);
        String deliveryId = "duplicate-delivery-id";

        // Enqueue first time
        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        // Enqueue second time (mocking the check in code)
        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertThat(jobRepository.findAll()).hasSize(1);
    }

    @Test
    void testUnsupportedEventReturns200AndDoesNotEnqueue() throws Exception {
        String payload = "{\"action\":\"created\"}";
        String signature = calculateSignature(payload);

        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "issue_comment")
                        .header("X-GitHub-Delivery", "comment-delivery-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertThat(jobRepository.findAll()).isEmpty();
    }

    @Test
    void testInvalidJsonPayloadReturns400() throws Exception {
        String payload = "{invalid-json-here}";
        String signature = calculateSignature(payload);

        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "bad-json-delivery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(jobRepository.findAll()).isEmpty();
    }

    @Test
    void testMissingRepositoryNameReturns400() throws Exception {
        String payload = "{\"ref\":\"refs/heads/main\",\"repository\":{}}";
        String signature = calculateSignature(payload);

        mockMvc.perform(post("/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "missing-repo-delivery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(jobRepository.findAll()).isEmpty();
    }
}
