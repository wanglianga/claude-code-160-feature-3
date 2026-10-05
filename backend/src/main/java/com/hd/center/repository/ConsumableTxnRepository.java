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

    /** 某批次的全部出入库流水（召回追溯依据） */
    List<ConsumableTxn> findByBatchIdOrderByCreatedAtDesc(Long batchId);

    /** 某批次流向治疗单的出库流水（预领/使用），用于定位受影响治疗记录 */
    List<ConsumableTxn> findByBatchIdAndTypeInAndSessionIdIsNotNull(Long batchId, Collection<TxnType> types);
}
