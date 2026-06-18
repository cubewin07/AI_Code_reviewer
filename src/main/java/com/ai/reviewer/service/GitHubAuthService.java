package com.ai.reviewer.service;

import com.ai.reviewer.config.AppConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class GitHubAuthService {

    private final AppConfig appConfig;
    private final GitHubJwtGenerator jwtGenerator;
    private final RestClient restClient;
    private final Map<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public GitHubAuthService(AppConfig appConfig, GitHubJwtGenerator jwtGenerator) {
        this.appConfig = appConfig;
        this.jwtGenerator = jwtGenerator;
        this.restClient = RestClient.builder()
                .baseUrl(appConfig.github().apiUrl())
                .build();
    }

    public String getInstallationToken(long installationId) {
        CachedToken cached = tokenCache.get(installationId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minus(1, ChronoUnit.MINUTES))) {
            return cached.token();
        }

        synchronized (this) {
            // Double check
            cached = tokenCache.get(installationId);
            if (cached != null && Instant.now().isBefore(cached.expiresAt().minus(1, ChronoUnit.MINUTES))) {
                return cached.token();
            }

            log.info("Fetching new installation access token for installation: {}", installationId);
            String jwt = jwtGenerator.generateJwt();
            InstallationTokenResponse response = restClient.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .header("Authorization", "Bearer " + jwt)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(InstallationTokenResponse.class);

            if (response == null || response.token() == null) {
                throw new RuntimeException("Failed to fetch installation access token: empty response");
            }

            Instant expiresAt = Instant.parse(response.expiresAt());
            tokenCache.put(installationId, new CachedToken(response.token(), expiresAt));
            return response.token();
        }
    }

    // Visible for testing to clear cache
    public void clearCache() {
        tokenCache.clear();
    }

    private record CachedToken(String token, Instant expiresAt) {}

    private record InstallationTokenResponse(
            String token,
            @JsonProperty("expires_at") String expiresAt
    ) {}
}
