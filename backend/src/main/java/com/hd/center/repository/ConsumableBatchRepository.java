package com.hd.center.repository;

import com.hd.center.entity.ConsumableBatch;
import com.hd.center.entity.Enums.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsumableBatchRepository extends JpaRepository<ConsumableBatch, Long> {

    List<ConsumableBatch> findByConsumableIdOrderByExpiryDateAscIdAsc(Long consumableId);

    List<ConsumableBatch> findAllByOrderByConsumableCodeAscIdDesc();

    Optional<ConsumableBatch> findByConsumableIdAndBatchNo(Long consumableId, String batchNo);

    List<ConsumableBatch> findByStatus(BatchStatus status);

    /** 某耗材可用批次（按近效期优先 FEFO） */
    List<ConsumableBatch> findByConsumableIdAndStatusOrderByExpiryDateAscIdAsc(Long consumableId, BatchStatus status);
}
