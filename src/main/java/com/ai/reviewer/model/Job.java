package com.ai.reviewer.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(name = "delivery_id", nullable = false)
    private String deliveryId;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Builder.Default
    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "error")
    private String error;

    @Column(name = "review_text")
    private String reviewText;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
