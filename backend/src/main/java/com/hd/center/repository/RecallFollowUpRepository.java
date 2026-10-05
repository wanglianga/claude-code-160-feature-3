package com.hd.center.repository;

import com.hd.center.entity.RecallFollowUp;
import com.hd.center.entity.Enums.FollowUpStatus;
import com.hd.center.entity.Enums.ReviewResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecallFollowUpRepository extends JpaRepository<RecallFollowUp, Long> {

    List<RecallFollowUp> findByRecallIdOrderBySessionDateDescIdDesc(Long recallId);

    List<RecallFollowUp> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    Optional<RecallFollowUp> findByRecallIdAndSessionId(Long recallId, Long sessionId);

    List<RecallFollowUp> findByRecallIdAndStatus(Long recallId, FollowUpStatus status);

    List<RecallFollowUp> findByRecallIdAndDiscomfortTrueAndReviewResult(Long recallId, ReviewResult reviewResult);

    long countByRecallIdAndStatus(Long recallId, FollowUpStatus status);
}
