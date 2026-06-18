package com.ai.reviewer.service;

import com.ai.reviewer.config.AppConfig;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubJwtGenerator {

    private final AppConfig appConfig;
    private final ResourceLoader resourceLoader;
    private PrivateKey cachedPrivateKey;

    public String generateJwt() {
        try {
            PrivateKey privateKey = getPrivateKey();
            Instant now = Instant.now();
            return JWT.create()
                    .withIssuer(appConfig.github().appId())
                    .withIssuedAt(now.minusSeconds(60)) // Back-dated by 60s for clock drift
                    .withExpiresAt(now.plusSeconds(9 * 60)) // 9 mins expiry
                    .sign(Algorithm.RSA256(null, (RSAPrivateKey) privateKey));
        } catch (Exception e) {
            log.error("Failed to generate GitHub App JWT", e);
            throw new RuntimeException("Failed to generate GitHub App JWT", e);
        }
    }

    private synchronized PrivateKey getPrivateKey() throws Exception {
        if (cachedPrivateKey != null) {
            return cachedPrivateKey;
        }

        String path = appConfig.github().privateKeyPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("GitHub App private key path is not configured");
        }

        log.info("Loading GitHub App private key from path: {}", path);
        String pemContent;

        // 1. Try loading as a Spring resource
        Resource resource = resourceLoader.getResource(path);
        if (resource.exists()) {
            try (InputStream is = resource.getInputStream()) {
                pemContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            // 2. Try loading from file system directly
            Path filePath = Paths.get(path);
            if (Files.exists(filePath)) {
                pemContent = Files.readString(filePath, StandardCharsets.UTF_8);
            } else {
                // 3. Fallback to classpath resource directly
                Resource classpathResource = resourceLoader.getResource("classpath:" + path);
                if (classpathResource.exists()) {
                    try (InputStream is = classpathResource.getInputStream()) {
                        pemContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } else {
                    throw new FileNotFoundException("Could not find GitHub App private key PEM file at: " + path);
                }
            }
        }

        cachedPrivateKey = parsePrivateKey(pemContent);
        return cachedPrivateKey;
    }

    private PrivateKey parsePrivateKey(String pemContent) throws Exception {
        try (PEMParser pemParser = new PEMParser(new StringReader(pemContent))) {
            Object object = pemParser.readObject();
            if (object == null) {
                throw new IllegalArgumentException("Private key PEM content is empty or invalid");
            }

            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (object instanceof PrivateKeyInfo) {
                return converter.getPrivateKey((PrivateKeyInfo) object);
            } else if (object instanceof org.bouncycastle.openssl.PEMKeyPair) {
                return converter.getPrivateKey(((org.bouncycastle.openssl.PEMKeyPair) object).getPrivateKeyInfo());
            }
            throw new IllegalArgumentException("Unsupported private key type: " + object.getClass().getName());
        }
    }
}
