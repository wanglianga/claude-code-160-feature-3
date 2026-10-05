package com.hd.center.repository;

import com.hd.center.entity.RecallFollowup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecallFollowupRepository extends JpaRepository<RecallFollowup, Long> {
    List<RecallFollowup> findByRecallIdOrderById(Long recallId);
    List<RecallFollowup> findBySessionId(Long sessionId);
    long countByRecallIdAndStatusNot(Long recallId, com.hd.center.entity.Enums.FollowupStatus status);
}
