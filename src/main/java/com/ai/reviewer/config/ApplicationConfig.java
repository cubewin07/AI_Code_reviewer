package com.ai.reviewer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
public class ApplicationConfig {

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

