package com.ai.reviewer.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JobContext(
    Long jobId,
    String eventType,
    String repoFullName,
    long installationId,
    Integer prNumber,
    String commitSha,
    String ref,
    String baseSha,
    String headSha
) {
    public static JobContext fromWebhook(Long jobId, String eventType, String repoFullName, String payload, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            
            long installationId = 0;
            if (root.has("installation") && root.get("installation").has("id")) {
                installationId = root.get("installation").get("id").asLong();
            }

            Integer prNumber = null;
            String commitSha = null;
            String ref = null;
            String baseSha = null;
            String headSha = null;

            if ("pull_request".equalsIgnoreCase(eventType)) {
                if (root.has("number")) {
                    prNumber = root.get("number").asInt();
                } else if (root.has("pull_request") && root.get("pull_request").has("number")) {
                    prNumber = root.get("pull_request").get("number").asInt();
                }
                
                if (root.has("pull_request")) {
                    JsonNode prNode = root.get("pull_request");
                    if (prNode.has("head") && prNode.get("head").has("sha")) {
                        commitSha = prNode.get("head").get("sha").asText();
                        headSha = commitSha;
                    }
                    if (prNode.has("base") && prNode.get("base").has("sha")) {
                        baseSha = prNode.get("base").get("sha").asText();
                    }
                }
            } else if ("push".equalsIgnoreCase(eventType)) {
                if (root.has("ref")) {
                    ref = root.get("ref").asText();
                }
                if (root.has("before")) {
                    baseSha = root.get("before").asText();
                }
                if (root.has("after")) {
                    commitSha = root.get("after").asText();
                    headSha = commitSha;
                }
            }

            return new JobContext(
                jobId,
                eventType,
                repoFullName,
                installationId,
                prNumber,
                commitSha,
                ref,
                baseSha,
                headSha
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JobContext from payload", e);
        }
    }
    
    public String getOwner() {
        if (repoFullName == null || !repoFullName.contains("/")) {
            return "";
        }
        return repoFullName.split("/")[0];
    }

    public String getRepo() {
        if (repoFullName == null || !repoFullName.contains("/")) {
            return repoFullName;
        }
        String[] parts = repoFullName.split("/");
        return parts.length > 1 ? parts[1] : "";
    }
}
