package com.ai.reviewer.repository;

import com.ai.reviewer.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {
    boolean existsByRepoFullNameAndEventTypeAndDeliveryId(String repoFullName, String eventType, String deliveryId);
}
