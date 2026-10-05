package com.hd.center.repository;

import com.hd.center.entity.ConsumableBatch;
import com.hd.center.entity.Enums.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConsumableBatchRepository extends JpaRepository<ConsumableBatch, Long> {
    List<ConsumableBatch> findByConsumableIdOrderByExpiryDateAsc(Long consumableId);
    List<ConsumableBatch> findByConsumableIdAndStatusOrderByExpiryDateAsc(Long consumableId, BatchStatus status);
    Optional<ConsumableBatch> findByConsumableIdAndBatchNo(Long consumableId, String batchNo);
    List<ConsumableBatch> findByStatusIn(Collection<BatchStatus> statuses);
    boolean existsByConsumableId(Long consumableId);
}
