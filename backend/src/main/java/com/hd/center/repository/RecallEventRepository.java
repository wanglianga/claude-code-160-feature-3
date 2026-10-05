package com.hd.center.repository;

import com.hd.center.entity.RecallEvent;
import com.hd.center.entity.Enums.RecallStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RecallEventRepository extends JpaRepository<RecallEvent, Long> {
    List<RecallEvent> findAllByOrderByCreatedAtDesc();
    List<RecallEvent> findByStatusInOrderByCreatedAtDesc(Collection<RecallStatus> statuses);
    boolean existsByBatchIdAndStatusIn(Long batchId, Collection<RecallStatus> statuses);
    boolean existsByRecallNo(String recallNo);
    List<RecallEvent> findByBatchId(Long batchId);
}
