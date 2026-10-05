package com.hd.center.repository;

import com.hd.center.entity.RecallOrder;
import com.hd.center.entity.Enums.RecallStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecallOrderRepository extends JpaRepository<RecallOrder, Long> {

    List<RecallOrder> findAllByOrderByCreatedAtDesc();

    List<RecallOrder> findByStatusNotOrderByCreatedAtDesc(RecallStatus status);

    List<RecallOrder> findByStatusOrderByCreatedAtDesc(RecallStatus status);

    boolean existsByConsumableIdAndBatchNoAndStatusNot(Long consumableId, String batchNo, RecallStatus status);

    List<RecallOrder> findBySupplierOrderByCreatedAtDesc(String supplier);
}
