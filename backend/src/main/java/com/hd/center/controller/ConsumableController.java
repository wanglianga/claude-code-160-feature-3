package com.hd.center.controller;

import com.hd.center.config.CurrentUser;
import com.hd.center.entity.Consumable;
import com.hd.center.entity.ConsumableBatch;
import com.hd.center.entity.ConsumableTxn;
import com.hd.center.entity.Enums.TxnType;
import com.hd.center.repository.ConsumableBatchRepository;
import com.hd.center.repository.ConsumableRepository;
import com.hd.center.repository.ConsumableTxnRepository;
import com.hd.center.service.ConsumableService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/consumables")
@RequiredArgsConstructor
public class ConsumableController {

    private final ConsumableRepository consumableRepo;
    private final ConsumableBatchRepository batchRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ConsumableService consumableService;

    @GetMapping
    public List<Consumable> list() {
        return consumableRepo.findAllByOrderByCode();
    }

    @PostMapping
    public Consumable create(@RequestBody Consumable consumable) {
        consumable.setId(null);
        return consumableRepo.save(consumable);
    }

    @PutMapping("/{id}")
    public Consumable update(@PathVariable Long id, @RequestBody Consumable body) {
        Consumable c = get(id);
        c.setName(body.getName());
        c.setSpec(body.getSpec());
        c.setSupplier(body.getSupplier());
        c.setUnit(body.getUnit());
        c.setSafeStock(body.getSafeStock());
        c.setSelfPay(body.getSelfPay());
        c.setPrice(body.getPrice());
        return consumableRepo.save(c);
    }

    // ---------------- 批次 ----------------

    /** 批次台账：可按耗材过滤 */
    @GetMapping("/batches")
    public List<ConsumableBatch> batches(@RequestParam(required = false) Long consumableId) {
        if (consumableId != null) {
            return batchRepo.findByConsumableIdOrderByExpiryDateAscIdAsc(consumableId);
        }
        return batchRepo.findAllByOrderByConsumableCodeAscIdDesc();
    }

    public record BatchInboundRequest(@NotNull String batchNo, String supplier,
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate productionDate,
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
                                      @NotNull Integer qty, String note) {}

    /** 批次入库（同批号累加库存） */
    @PostMapping("/{id}/batches")
    public ConsumableBatch inbound(@PathVariable Long id, @RequestBody BatchInboundRequest request,
                                   @AuthenticationPrincipal CurrentUser current) {
        Consumable c = get(id);
        return consumableService.inboundBatch(c, request.batchNo().trim(), request.supplier(),
                request.productionDate(), request.expiryDate(), request.qty(), current.name(), request.note());
    }

    /** 某批次出入库流水（召回追溯） */
    @GetMapping("/batches/{batchId}/txns")
    public List<ConsumableTxn> batchTxns(@PathVariable Long batchId) {
        return txnRepo.findByBatchIdOrderByCreatedAtDesc(batchId);
    }

    // ---------------- 出入库 ----------------

    public record TxnRequest(@NotNull TxnType type, @NotNull Integer qty, Long sessionId,
                             String patientName, String note, Long batchId) {}

    /**
     * 耗材出库（使用/损耗/预领）：
     * 传 batchId 时从指定批次出库（召回冻结批次会被拒绝）；不传时按 FEFO 自动挑批。
     * 归还须走 /return 接口以退回原批次。
     */
    @PostMapping("/{id}/txn")
    public List<ConsumableTxn> transact(@PathVariable Long id, @RequestBody TxnRequest request,
                                        @AuthenticationPrincipal CurrentUser current) {
        Consumable c = get(id);
        if (request.type() == TxnType.RETURN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归还请使用 /api/consumables/{id}/return 接口");
        }
        if (request.type() == TxnType.RECALL_OUT || request.type() == TxnType.BATCH_IN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该流水类型由系统在批次入库/召回流程中自动生成");
        }
        if (request.batchId() != null) {
            ConsumableBatch batch = batchRepo.findById(request.batchId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在"));
            if (!batch.getConsumableId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次与耗材不匹配");
            }
            return List.of(consumableService.outboundBatch(c, batch, request.type(), request.qty(),
                    request.sessionId(), request.patientName(), current.name(), request.note()));
        }
        return consumableService.outboundFefo(c, request.type(), request.qty(), request.sessionId(),
                request.patientName(), current.name(), request.note());
    }

    public record ReturnRequest(@NotNull Integer qty, Long batchId, Long sessionId,
                                String patientName, String note) {}

    /** 归还入库：退回指定批次（不传 batchId 则总量归还） */
    @PostMapping("/{id}/return")
    public ConsumableTxn returning(@PathVariable Long id, @RequestBody ReturnRequest request,
                                   @AuthenticationPrincipal CurrentUser current) {
        Consumable c = get(id);
        if (request.batchId() != null) {
            ConsumableBatch batch = batchRepo.findById(request.batchId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在"));
            return consumableService.returnToBatch(c, batch, request.qty(), request.sessionId(),
                    request.patientName(), current.name(), request.note());
        }
        return consumableService.transactUnbatched(c, TxnType.RETURN, request.qty(), request.sessionId(),
                request.patientName(), current.name(), request.note());
    }

    @GetMapping("/txns")
    public List<ConsumableTxn> txns(@RequestParam(required = false) Long sessionId) {
        if (sessionId != null) {
            return txnRepo.findBySessionId(sessionId);
        }
        return txnRepo.findTop100ByOrderByCreatedAtDesc();
    }

    /** 库存预警：低于安全库存的耗材 */
    @GetMapping("/warnings")
    public List<Map<String, Object>> warnings() {
        return consumableRepo.findAllByOrderByCode().stream()
                .filter(c -> c.getStock() <= c.getSafeStock())
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "name", c.getName(),
                        "stock", c.getStock(),
                        "safeStock", c.getSafeStock(),
                        "unit", c.getUnit() == null ? "" : c.getUnit()))
                .toList();
    }

    private Consumable get(Long id) {
        return consumableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "耗材不存在"));
    }
}
