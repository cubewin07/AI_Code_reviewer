package com.ai.reviewer.client;

import com.ai.reviewer.dto.github.CommitInfo;
import com.ai.reviewer.dto.github.FileDiff;
import com.ai.reviewer.dto.github.PullRequestMeta;
import com.ai.reviewer.service.GitHubAuthService;
import com.ai.reviewer.service.GitHubJwtGenerator;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GitHubClientTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private GitHubJwtGenerator jwtGenerator;

    @Autowired
    private GitHubAuthService authService;

    @Autowired
    private GitHubClient gitHubClient;

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
    void resetWireMockAndCache() {
        wireMockServer.resetAll();
        authService.clearCache();
    }

    @Test
    void testJwtGeneration() {
        String jwt = jwtGenerator.generateJwt();
        assertThat(jwt).isNotEmpty();

        DecodedJWT decoded = JWT.decode(jwt);
        assertThat(decoded.getIssuer()).isEqualTo("123456");
        assertThat(decoded.getAlgorithm()).isEqualTo("RS256");
        assertThat(decoded.getExpiresAtAsInstant()).isAfter(Instant.now());
    }

    @Test
    void testAuthServiceRetrievesAndCachesToken() {
        // Stub for access token POST
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"test-installation-token-123\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        // First call
        String token1 = authService.getInstallationToken(123L);
        assertThat(token1).isEqualTo("test-installation-token-123");

        // Second call (should be cached)
        String token2 = authService.getInstallationToken(123L);
        assertThat(token2).isEqualTo("test-installation-token-123");

        // Verify POST was only called once
        verify(1, postRequestedFor(urlEqualTo("/app/installations/123/access_tokens")));
    }

    @Test
    void testGetPullRequestMetadata() {
        // Stub access token
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        // Stub PR metadata
        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/pulls/5"))
                .withHeader("Authorization", equalTo("token mock-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"number\":5,\"state\":\"open\",\"title\":\"Test PR\",\"body\":\"PR body\",\"head\":{\"sha\":\"1111\",\"ref\":\"head-ref\",\"repo\":{\"full_name\":\"test-owner/test-repo\"}},\"base\":{\"sha\":\"0000\",\"ref\":\"base-ref\",\"repo\":{\"full_name\":\"test-owner/test-repo\"}}}")));

        PullRequestMeta meta = gitHubClient.getPullRequestMetadata("test-owner", "test-repo", 5, 123L);
        assertThat(meta).isNotNull();
        assertThat(meta.number()).isEqualTo(5);
        assertThat(meta.state()).isEqualTo("open");
        assertThat(meta.title()).isEqualTo("Test PR");
        assertThat(meta.body()).isEqualTo("PR body");
        assertThat(meta.head().sha()).isEqualTo("1111");
        assertThat(meta.base().sha()).isEqualTo("0000");
    }

    @Test
    void testGetCommitInfo() {
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/commits/1111"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\":\"1111\",\"commit\":{\"message\":\"Commit message\",\"author\":{\"name\":\"Author Name\",\"email\":\"author@example.com\",\"date\":\"2026-06-18T10:00:00Z\"}}}")));

        CommitInfo commitInfo = gitHubClient.getCommitInfo("test-owner", "test-repo", "1111", 123L);
        assertThat(commitInfo).isNotNull();
        assertThat(commitInfo.sha()).isEqualTo("1111");
        assertThat(commitInfo.commit().message()).isEqualTo("Commit message");
        assertThat(commitInfo.commit().author().name()).isEqualTo("Author Name");
        assertThat(commitInfo.commit().author().email()).isEqualTo("author@example.com");
    }

    @Test
    void testGetPullRequestDiff() {
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/pulls/5/files"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"filename\":\"App.java\",\"status\":\"modified\",\"additions\":5,\"deletions\":2,\"changes\":7,\"patch\":\"@@ -1,3 +1,6 @@\"}]")));

        List<FileDiff> diffs = gitHubClient.getPullRequestDiff("test-owner", "test-repo", 5, 123L);
        assertThat(diffs).hasSize(1);
        FileDiff diff = diffs.get(0);
        assertThat(diff.filename()).isEqualTo("App.java");
        assertThat(diff.status()).isEqualTo("modified");
        assertThat(diff.additions()).isEqualTo(5);
        assertThat(diff.deletions()).isEqualTo(2);
        assertThat(diff.patch()).isEqualTo("@@ -1,3 +1,6 @@");
    }

    @Test
    void testGetCommitDiff() {
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/compare/0000...1111"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"files\":[{\"filename\":\"App.java\",\"status\":\"modified\",\"additions\":5,\"deletions\":2,\"changes\":7,\"patch\":\"@@ -1,3 +1,6 @@\"}]}")));

        List<FileDiff> diffs = gitHubClient.getCommitDiff("test-owner", "test-repo", "0000", "1111", 123L);
        assertThat(diffs).hasSize(1);
        FileDiff diff = diffs.get(0);
        assertThat(diff.filename()).isEqualTo("App.java");
        assertThat(diff.status()).isEqualTo("modified");
        assertThat(diff.patch()).isEqualTo("@@ -1,3 +1,6 @@");
    }

    @Test
    void testGetFileContentAndDecode() {
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        // File path has slashes: src/main/java/App.java
        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/contents/src/main/java/App.java?ref=1111"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"file\",\"encoding\":\"base64\",\"size\":21,\"name\":\"App.java\",\"path\":\"src/main/java/App.java\",\"content\":\"cHVibGljIGNsYXNzIEFwcCB7fQ==\",\"sha\":\"2222\"}")));

        String content = gitHubClient.getFileContent("test-owner", "test-repo", "src/main/java/App.java", "1111", 123L);
        assertThat(content).isEqualTo("public class App {}");
    }

    @Test
    void testGetReadmeAndDecode() {
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/readme"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"file\",\"encoding\":\"base64\",\"size\":9,\"name\":\"README.md\",\"path\":\"README.md\",\"content\":\"IyBNeSBSZXBv\",\"sha\":\"3333\"}")));

        String readme = gitHubClient.getReadme("test-owner", "test-repo", 123L);
        assertThat(readme).isEqualTo("# My Repo");
    }

    @Test
    void testGetReadmeNotFoundReturnsDefaultString() {
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/readme"))
                .willReturn(aResponse()
                        .withStatus(404)));

        String readme = gitHubClient.getReadme("test-owner", "test-repo", 123L);
        assertThat(readme).isEqualTo("No README file found for this repository.");
    }

    @Test
    void testRateLimiterInterceptorRetries() {
        // Stub access token
        stubFor(post(urlEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"mock-token\",\"expires_at\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")));

        // Scenario: First call returns 429, second call returns 200
        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/pulls/10"))
                .inScenario("Rate Limiting Scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "1"))
                .willSetStateTo("Rate Limit Cleared"));

        stubFor(get(urlEqualTo("/repos/test-owner/test-repo/pulls/10"))
                .inScenario("Rate Limiting Scenario")
                .whenScenarioStateIs("Rate Limit Cleared")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"number\":10,\"state\":\"open\",\"title\":\"Retry PR\",\"body\":\"PR body\",\"head\":{\"sha\":\"1111\",\"ref\":\"head-ref\",\"repo\":{\"full_name\":\"test-owner/test-repo\"}},\"base\":{\"sha\":\"0000\",\"ref\":\"base-ref\",\"repo\":{\"full_name\":\"test-owner/test-repo\"}}}")));

        PullRequestMeta meta = gitHubClient.getPullRequestMetadata("test-owner", "test-repo", 10, 123L);
        assertThat(meta).isNotNull();
        assertThat(meta.number()).isEqualTo(10);
        assertThat(meta.title()).isEqualTo("Retry PR");

        // Verify both calls were made
        verify(2, getRequestedFor(urlEqualTo("/repos/test-owner/test-repo/pulls/10")));
    }
}
