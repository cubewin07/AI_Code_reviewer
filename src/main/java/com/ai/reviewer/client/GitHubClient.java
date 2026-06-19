package com.ai.reviewer.client;

import com.ai.reviewer.config.AppConfig;
import com.ai.reviewer.dto.github.*;
import com.ai.reviewer.service.GitHubAuthService;
import com.ai.reviewer.service.GitHubRateLimitInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class GitHubClient {

    private static final int MAX_CONTENT_CHARS = 20000;

    private final AppConfig appConfig;
    private final GitHubAuthService authService;
    private final RestClient restClient;

    public GitHubClient(AppConfig appConfig, GitHubAuthService authService, GitHubRateLimitInterceptor rateLimitInterceptor) {
        this.appConfig = appConfig;
        this.authService = authService;
        this.restClient = RestClient.builder()
                .baseUrl(appConfig.github().apiUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "AI-Code-Reviewer-App")
                .requestInterceptor(rateLimitInterceptor)
                .build();
    }

    public PullRequestMeta getPullRequestMetadata(String owner, String repo, int prNumber, long installationId) {
        try {
            log.info("Fetching PR metadata for repo: {}/{}, PR number: {}", owner, repo, prNumber);
            String token = authService.getInstallationToken(installationId);
            return restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(PullRequestMeta.class);
        } catch (Exception e) {
            log.error("Failed to fetch PR metadata for repo {}/{} PR {}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to fetch PR metadata", e);
        }
    }

    public CommitInfo getCommitInfo(String owner, String repo, String sha, long installationId) {
        try {
            log.info("Fetching commit info for repo: {}/{}, SHA: {}", owner, repo, sha);
            String token = authService.getInstallationToken(installationId);
            return restClient.get()
                    .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(CommitInfo.class);
        } catch (Exception e) {
            log.error("Failed to fetch commit info for repo {}/{} SHA {}", owner, repo, sha, e);
            throw new RuntimeException("Failed to fetch commit info", e);
        }
    }

    public List<FileDiff> getPullRequestDiff(String owner, String repo, int prNumber, long installationId) {
        try {
            log.info("Fetching PR diff for repo: {}/{}, PR number: {}", owner, repo, prNumber);
            String token = authService.getInstallationToken(installationId);
            FileDiff[] response = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/files", owner, repo, prNumber)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(FileDiff[].class);
            return response != null ? Arrays.asList(response) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch PR diff for repo {}/{} PR {}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to fetch PR diff", e);
        }
    }

    public List<FileDiff> getCommitDiff(String owner, String repo, String base, String head, long installationId) {
        try {
            log.info("Fetching commit diff/comparison for repo: {}/{}, {}...{}", owner, repo, base, head);
            String token = authService.getInstallationToken(installationId);
            CompareResponse response = restClient.get()
                    .uri("/repos/{owner}/{repo}/compare/{base}...{head}", owner, repo, base, head)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(CompareResponse.class);
            return response != null && response.files() != null ? response.files() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch commit diff for repo {}/{} compare {}...{}", owner, repo, base, head, e);
            throw new RuntimeException("Failed to fetch commit diff", e);
        }
    }

    public String getFileContent(String owner, String repo, String path, String ref, long installationId) {
        try {
            log.info("Fetching file content for path: {} in repo: {}/{} at ref: {}", path, owner, repo, ref);
            String token = authService.getInstallationToken(installationId);
            
            // Build URI manually to prevent URL-encoding slashes in the file path
            URI uri = UriComponentsBuilder.fromUriString(appConfig.github().apiUrl())
                    .path("/repos/" + owner + "/" + repo + "/contents/" + path)
                    .queryParam("ref", ref)
                    .build()
                    .toUri();

            GitHubContentResponse response = restClient.get()
                    .uri(uri)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(GitHubContentResponse.class);

            if (response != null && "base64".equalsIgnoreCase(response.encoding())) {
                return decodeAndTruncate(response.content());
            }
            return "";
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("File {} not found in repo: {}/{} at ref: {}", path, owner, repo, ref);
            return "File not found.";
        } catch (Exception e) {
            log.error("Failed to fetch file content for {} in repo: {}/{} at ref: {}", path, owner, repo, ref, e);
            throw new RuntimeException("Failed to fetch file content", e);
        }
    }

    public String getReadme(String owner, String repo, long installationId) {
        try {
            log.info("Fetching README for repo: {}/{}", owner, repo);
            String token = authService.getInstallationToken(installationId);
            GitHubContentResponse response = restClient.get()
                    .uri("/repos/{owner}/{repo}/readme", owner, repo)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(GitHubContentResponse.class);

            if (response != null && "base64".equalsIgnoreCase(response.encoding())) {
                return decodeAndTruncate(response.content());
            }
            return "";
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("README not found for repo: {}/{}", owner, repo);
            return "No README file found for this repository.";
        } catch (Exception e) {
            log.error("Failed to fetch README for repo: {}/{}", owner, repo, e);
            throw new RuntimeException("Failed to fetch README", e);
        }
    }

    public List<GitHubComment> getPullRequestComments(String owner, String repo, int prNumber, long installationId) {
        try {
            log.info("Fetching comments for PR: {}/{}, number: {}", owner, repo, prNumber);
            String token = authService.getInstallationToken(installationId);
            GitHubComment[] response = restClient.get()
                    .uri("/repos/{owner}/{repo}/issues/{number}/comments", owner, repo, prNumber)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(GitHubComment[].class);
            return response != null ? Arrays.asList(response) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch PR comments for repo {}/{} PR {}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to fetch PR comments", e);
        }
    }

    public List<GitHubComment> getCommitComments(String owner, String repo, String sha, long installationId) {
        try {
            log.info("Fetching comments for commit: {}/{}, SHA: {}", owner, repo, sha);
            String token = authService.getInstallationToken(installationId);
            GitHubComment[] response = restClient.get()
                    .uri("/repos/{owner}/{repo}/commits/{sha}/comments", owner, repo, sha)
                    .header("Authorization", "token " + token)
                    .retrieve()
                    .body(GitHubComment[].class);
            return response != null ? Arrays.asList(response) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch commit comments for repo {}/{} SHA {}", owner, repo, sha, e);
            throw new RuntimeException("Failed to fetch commit comments", e);
        }
    }

    public GitHubComment postPullRequestComment(String owner, String repo, int prNumber, String body, long installationId) {
        try {
            log.info("Posting comment to PR: {}/{}, number: {}", owner, repo, prNumber);
            String token = authService.getInstallationToken(installationId);
            return restClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{number}/comments", owner, repo, prNumber)
                    .header("Authorization", "token " + token)
                    .body(java.util.Map.of("body", body))
                    .retrieve()
                    .body(GitHubComment.class);
        } catch (Exception e) {
            log.error("Failed to post comment to PR {}/{} PR {}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to post PR comment", e);
        }
    }

    public GitHubComment postCommitComment(String owner, String repo, String sha, String body, long installationId) {
        try {
            log.info("Posting comment to commit: {}/{}, SHA: {}", owner, repo, sha);
            String token = authService.getInstallationToken(installationId);
            return restClient.post()
                    .uri("/repos/{owner}/{repo}/commits/{sha}/comments", owner, repo, sha)
                    .header("Authorization", "token " + token)
                    .body(java.util.Map.of("body", body))
                    .retrieve()
                    .body(GitHubComment.class);
        } catch (Exception e) {
            log.error("Failed to post comment to commit {}/{} SHA {}", owner, repo, sha, e);
            throw new RuntimeException("Failed to post commit comment", e);
        }
    }

    public GitHubComment updatePullRequestComment(String owner, String repo, long commentId, String body, long installationId) {
        try {
            log.info("Updating PR comment ID: {} in repo {}/{}", commentId, owner, repo);
            String token = authService.getInstallationToken(installationId);
            return restClient.patch()
                    .uri("/repos/{owner}/{repo}/issues/comments/{commentId}", owner, repo, commentId)
                    .header("Authorization", "token " + token)
                    .body(java.util.Map.of("body", body))
                    .retrieve()
                    .body(GitHubComment.class);
        } catch (Exception e) {
            log.error("Failed to update PR comment {} in repo {}/{}", commentId, owner, repo, e);
            throw new RuntimeException("Failed to update PR comment", e);
        }
    }

    public GitHubComment updateCommitComment(String owner, String repo, long commentId, String body, long installationId) {
        try {
            log.info("Updating commit comment ID: {} in repo {}/{}", commentId, owner, repo);
            String token = authService.getInstallationToken(installationId);
            return restClient.patch()
                    .uri("/repos/{owner}/{repo}/comments/{commentId}", owner, repo, commentId)
                    .header("Authorization", "token " + token)
                    .body(java.util.Map.of("body", body))
                    .retrieve()
                    .body(GitHubComment.class);
        } catch (Exception e) {
            log.error("Failed to update commit comment {} in repo {}/{}", commentId, owner, repo, e);
            throw new RuntimeException("Failed to update commit comment", e);
        }
    }

    private String decodeAndTruncate(String base64Content) {
        if (base64Content == null) {
            return "";
        }
        String cleaned = base64Content.replaceAll("\\s", "");
        byte[] decodedBytes = java.util.Base64.getDecoder().decode(cleaned);
        String text = new String(decodedBytes, StandardCharsets.UTF_8);
        if (text.length() > MAX_CONTENT_CHARS) {
            text = text.substring(0, MAX_CONTENT_CHARS) + "\n\n[Content truncated due to size limit...]";
        }
        return text;
    }
}
