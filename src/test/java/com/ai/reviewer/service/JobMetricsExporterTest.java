package com.ai.reviewer.service;

import com.ai.reviewer.model.Job;
import com.ai.reviewer.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.worker.poll-delay-ms=60000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobMetricsExporterTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        public io.prometheus.metrics.model.registry.PrometheusRegistry prometheusRegistry() {
            return new io.prometheus.metrics.model.registry.PrometheusRegistry();
        }

        @org.springframework.context.annotation.Bean
        public io.micrometer.prometheusmetrics.PrometheusMeterRegistry prometheusMeterRegistry(
                io.prometheus.metrics.model.registry.PrometheusRegistry prometheusRegistry) {
            return new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                    io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT,
                    prometheusRegistry,
                    io.micrometer.core.instrument.Clock.SYSTEM);
        }

        @org.springframework.context.annotation.Bean
        public org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint prometheusScrapeEndpoint(
                io.prometheus.metrics.model.registry.PrometheusRegistry prometheusRegistry) {
            return new org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint(prometheusRegistry);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
    }

    @Test
    void testDatabaseMetricsGauges() {
        // Save test jobs
        saveJob("PENDING", "del-1");
        saveJob("PENDING", "del-2");
        saveJob("IN_PROGRESS", "del-3");
        saveJob("COMPLETED", "del-4");
        saveJob("COMPLETED", "del-5");
        saveJob("COMPLETED", "del-6");
        saveJob("FAILED", "del-7");
        saveJob("DEAD_LETTER", "del-8");

        // Verify gauge counts
        assertThat(getGaugeValue("PENDING")).isEqualTo(2.0);
        assertThat(getGaugeValue("IN_PROGRESS")).isEqualTo(1.0);
        assertThat(getGaugeValue("COMPLETED")).isEqualTo(3.0);
        assertThat(getGaugeValue("FAILED")).isEqualTo(1.0);
        assertThat(getGaugeValue("DEAD_LETTER")).isEqualTo(1.0);

        // Update a job to verify gauges update dynamically
        Job pendingJob = jobRepository.findAll().stream()
                .filter(j -> "PENDING".equals(j.getStatus()))
                .findFirst()
                .orElseThrow();
        pendingJob.setStatus("IN_PROGRESS");
        jobRepository.save(pendingJob);

        // Assert updated gauge counts
        assertThat(getGaugeValue("PENDING")).isEqualTo(1.0);
        assertThat(getGaugeValue("IN_PROGRESS")).isEqualTo(2.0);
    }

    @Test
    void testPrometheusEndpointExposesGauges() throws Exception {
        System.out.println("MeterRegistry class: " + meterRegistry.getClass().getName());
        if (meterRegistry instanceof io.micrometer.core.instrument.composite.CompositeMeterRegistry composite) {
            System.out.println("Composite registries: " + composite.getRegistries());
        }

        saveJob("PENDING", "del-9");
        saveJob("PENDING", "del-10");
        saveJob("IN_PROGRESS", "del-11");

        String actuatorResponse = mockMvc.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        System.out.println("Actuator response: " + actuatorResponse);

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jobs_database_count{status=\"PENDING\"} 2.0")))
                .andExpect(content().string(containsString("jobs_database_count{status=\"IN_PROGRESS\"} 1.0")));
    }

    private void saveJob(String status, String deliveryId) {
        Job job = Job.builder()
                .eventType("push")
                .repoFullName("owner/repo")
                .deliveryId(deliveryId)
                .payload("{}")
                .status(status)
                .attempts(0)
                .build();
        jobRepository.save(job);
    }

    private double getGaugeValue(String status) {
        Gauge gauge = meterRegistry.find("jobs.database.count")
                .tag("status", status)
                .gauge();
        assertThat(gauge).withFailMessage("Gauge for status %s not found", status).isNotNull();
        return gauge.value();
    }
}
