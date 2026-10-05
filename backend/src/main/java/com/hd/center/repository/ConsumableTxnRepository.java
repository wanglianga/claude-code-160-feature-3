package com.hd.center.repository;

import com.hd.center.entity.ConsumableTxn;
import com.hd.center.entity.Enums.TxnType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ConsumableTxnRepository extends JpaRepository<ConsumableTxn, Long> {
    List<ConsumableTxn> findBySessionId(Long sessionId);
    List<ConsumableTxn> findByTypeAndSessionIdIn(TxnType type, Collection<Long> sessionIds);
    List<ConsumableTxn> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
    List<ConsumableTxn> findTop100ByOrderByCreatedAtDesc();
    List<ConsumableTxn> findByBatchId(Long batchId);
    List<ConsumableTxn> findByBatchIdAndSessionId(Long batchId, Long sessionId);
    List<ConsumableTxn> findByBatchIdIn(Collection<Long> batchIds);
}
