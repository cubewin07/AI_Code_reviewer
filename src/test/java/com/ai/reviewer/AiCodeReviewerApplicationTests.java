package com.ai.reviewer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "app.worker.poll-delay-ms=60000"
})
@ActiveProfiles("test")
class AiCodeReviewerApplicationTests {

    @Test
    void contextLoads() {
    }
}
