package com.ai.reviewer.repository;

import com.ai.reviewer.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {
    boolean existsByRepoFullNameAndEventTypeAndDeliveryId(String repoFullName, String eventType, String deliveryId);

    @Query("SELECT j.id FROM Job j WHERE j.status IN ('PENDING', 'FAILED') AND j.attempts < :maxAttempts ORDER BY j.id ASC")
    List<Long> findCandidateJobIds(@Param("maxAttempts") int maxAttempts);

    @Modifying
    @Transactional
    @Query("UPDATE Job j SET j.status = :status, j.updatedAt = :now WHERE j.id = :id AND j.status IN ('PENDING', 'FAILED')")
    int acquireJob(@Param("id") Long id, @Param("status") String status, @Param("now") LocalDateTime now);

    @Query("SELECT j FROM Job j WHERE j.status = 'IN_PROGRESS' AND j.updatedAt < :threshold")
    List<Job> findStuckJobs(@Param("threshold") LocalDateTime threshold);

    long countByStatus(String status);
}

