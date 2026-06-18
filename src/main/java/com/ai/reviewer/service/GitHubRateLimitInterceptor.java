package com.ai.reviewer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
@Slf4j
public class GitHubRateLimitInterceptor implements ClientHttpRequestInterceptor {

    private static final int MAX_ATTEMPTS = 3;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        int attempt = 0;
        long backoffMs = 1000;

        while (true) {
            attempt++;
            ClientHttpResponse response = execution.execute(request, body);

            // Check if status code is 429 (Too Many Requests) or 403 (Forbidden due to rate limit/abuse)
            if (response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) ||
                    (response.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN) && isRateLimitOrAbuse(response))) {

                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("GitHub API rate limit hit. Max attempts ({}) reached. Returning response.", MAX_ATTEMPTS);
                    return response;
                }

                long waitSecs = getRetryAfterSeconds(response);
                long sleepMs = waitSecs > 0 ? waitSecs * 1000 : backoffMs * attempt;

                log.warn("GitHub API rate limit hit (status {}). Backing off for {} ms before retry (attempt {}/{})",
                        response.getStatusCode(), sleepMs, attempt, MAX_ATTEMPTS);

                // Close the response to release connection resources
                response.close();

                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during rate limit backoff", e);
                }

                // Retry
                continue;
            }

            return response;
        }
    }

    private boolean isRateLimitOrAbuse(ClientHttpResponse response) {
        String remaining = response.getHeaders().getFirst("X-RateLimit-Remaining");
        String retryAfter = response.getHeaders().getFirst("Retry-After");
        return "0".equals(remaining) || retryAfter != null;
    }

    private long getRetryAfterSeconds(ClientHttpResponse response) {
        String retryAfter = response.getHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        String resetHeader = response.getHeaders().getFirst("X-RateLimit-Reset");
        if (resetHeader != null) {
            try {
                long resetEpochSec = Long.parseLong(resetHeader);
                long currentEpochSec = Instant.now().getEpochSecond();
                return Math.max(0, resetEpochSec - currentEpochSec);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return 0;
    }
}
