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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/consumables")
@RequiredArgsConstructor
public class ConsumableController {

    private final ConsumableRepository consumableRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ConsumableBatchRepository batchRepo;
    private final ConsumableService consumableService;

    /** 耗材列表（含冻结/召回占用库存） */
    @GetMapping
    public List<Consumable> list() {
        List<Consumable> all = consumableRepo.findAllByOrderByCode();
        all.forEach(c -> c.setFrozenQty(consumableService.frozenQty(c.getId())));
        return all;
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
        c.setUnit(body.getUnit());
        c.setSafeStock(body.getSafeStock());
        c.setSelfPay(body.getSelfPay());
        c.setPrice(body.getPrice());
        return consumableRepo.save(c);
    }

    public record TxnRequest(@NotNull TxnType type, @NotNull Integer qty, Long sessionId,
                             String patientName, String note, Long batchId,
                             String batchNo, String supplier,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate) {}

    /**
     * 耗材出入库（使用/归还/损耗/入库/预领）。
     * 出库可指定批次（冻结/召回批次会被拒绝），不指定则按 FEFO 自动分配正常批次；
     * 入库可携带批号+供应商+失效期，自动查找或创建批次。
     */
    @PostMapping("/{id}/txn")
    public List<ConsumableTxn> transact(@PathVariable Long id, @RequestBody TxnRequest request,
                                        @AuthenticationPrincipal CurrentUser current) {
        Consumable c = get(id);
        if (request.type() == TxnType.RESTOCK) {
            return List.of(consumableService.restock(c, request.qty(), request.batchNo(),
                    request.supplier(), request.expiryDate(), current.name(), request.note()));
        }
        return consumableService.transactBatch(c, request.type(), request.qty(), request.sessionId(),
                request.patientName(), current.name(), request.note(), request.batchId());
    }

    /** 批次列表（含剩余量），可按耗材过滤 */
    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(required = false) Long consumableId) {
        List<ConsumableBatch> batches = consumableId != null
                ? batchRepo.findByConsumableIdOrderByExpiryDateAsc(consumableId)
                : batchRepo.findAll();
        return batches.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", b.getId());
            m.put("consumableId", b.getConsumable().getId());
            m.put("consumableName", b.getConsumable().getName());
            m.put("batchNo", b.getBatchNo());
            m.put("supplier", b.getSupplier());
            m.put("productionDate", b.getProductionDate());
            m.put("expiryDate", b.getExpiryDate());
            m.put("receivedQty", b.getReceivedQty());
            m.put("remainingQty", consumableService.batchRemaining(b.getId()));
            m.put("status", b.getStatus().name());
            m.put("note", b.getNote());
            m.put("createdAt", b.getCreatedAt());
            return m;
        }).toList();
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
