package com.ai.reviewer.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.Executor;

@Configuration
public class ApplicationConfig {

    @PostConstruct
    void loadDotenv() {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(entry -> {
            if (System.getenv(entry.getKey()) == null && System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }

    @Bean(name = "jobExecutor")
    public Executor jobExecutor(AppConfig appConfig) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("job-worker-");
        executor.setVirtualThreads(true);
        int concurrency = appConfig.worker() != null ? appConfig.worker().concurrency() : 2;
        if (concurrency <= 0) {
            concurrency = 2;
        }
        executor.setConcurrencyLimit(concurrency);
        return executor;
    }
}

