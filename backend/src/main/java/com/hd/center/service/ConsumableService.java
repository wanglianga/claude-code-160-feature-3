package com.hd.center.service;

import com.hd.center.entity.*;
import com.hd.center.entity.Enums.BatchStatus;
import com.hd.center.entity.Enums.ExceptionType;
import com.hd.center.entity.Enums.TxnType;
import com.hd.center.repository.ConsumableBatchRepository;
import com.hd.center.repository.ConsumableRepository;
import com.hd.center.repository.ConsumableTxnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 耗材库存与流水。出库（预领/使用/损耗/召回出库）为负数量，入库（归还/补货/批次入库）为正数量。
 * 支持按厂家批号管理：出库按近效期优先（FEFO）自动挑批；召回期间批次冻结，任何出库被拦截。
 */
@Service
@RequiredArgsConstructor
public class ConsumableService {

    private final ConsumableRepository consumableRepo;
    private final ConsumableBatchRepository batchRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ExceptionService exceptionService;

    /** 单次透析标准预领套件：耗材编码 -> 数量 */
    public static final Map<String, Integer> STANDARD_KIT = Map.of(
            "DIALYZER", 1,
            "BLOODLINE", 1,
            "NEEDLE", 2,
            "HEPARIN", 1,
            "SALINE", 1,
            "DIALYSATE", 1);

    private static final List<TxnType> OUTBOUND_TYPES = List.of(TxnType.PRE_DRAW, TxnType.USE, TxnType.WASTE);

    /**
     * 批次入库（补货）：同批号累加库存；已退回厂家的批号不能再入库。
     */
    @Transactional
    public ConsumableBatch inboundBatch(Consumable consumable, String batchNo, String supplier,
                                        LocalDate productionDate, LocalDate expiryDate, int qty,
                                        String operator, String note) {
        if (qty <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入库数量必须大于 0");
        }
        ConsumableBatch batch = batchRepo.findByConsumableIdAndBatchNo(consumable.getId(), batchNo).orElse(null);
        if (batch == null) {
            batch = new ConsumableBatch();
            batch.setConsumableId(consumable.getId());
            batch.setConsumableCode(consumable.getCode());
            batch.setConsumableName(consumable.getName());
            batch.setBatchNo(batchNo);
            batch.setSupplier(supplier != null ? supplier : consumable.getSupplier());
            batch.setProductionDate(productionDate);
            batch.setExpiryDate(expiryDate);
            batch.setInboundQty(0);
            batch.setStockQty(0);
            batch.setStatus(BatchStatus.ACTIVE);
        } else {
            if (batch.getStatus() == BatchStatus.RETURNED) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批号 " + batchNo + " 已退回厂家，不能再入库");
            }
            if (batch.getStatus() == BatchStatus.FROZEN) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "批号 " + batchNo + " 因召回冻结中，暂停入库，请等待召回办结");
            }
            if (supplier != null) batch.setSupplier(supplier);
            if (productionDate != null) batch.setProductionDate(productionDate);
            if (expiryDate != null) batch.setExpiryDate(expiryDate);
        }
        batch.setInboundQty(batch.getInboundQty() + qty);
        batch.setStockQty(batch.getStockQty() + qty);
        if (batch.getStatus() == BatchStatus.DEPLETED) {
            batch.setStatus(BatchStatus.ACTIVE);
        }
        if (batch.getStockQty() > 0 && batch.getStatus() != BatchStatus.FROZEN) {
            batch.setStatus(BatchStatus.ACTIVE);
        }
        batch = batchRepo.save(batch);

        consumable.setStock(consumable.getStock() + qty);
        consumableRepo.save(consumable);
        writeTxn(consumable, batch, TxnType.BATCH_IN, qty, null, null, operator, note);
        return batch;
    }

    /**
     * 为一条治疗单预领标准套件：每种耗材按 FEFO 从可用批次拆取出库。
     * 冻结批次不会被发放；可用库存不足时只出能出的部分并产生「耗材不足」异常。
     */
    @Transactional
    public List<ConsumableTxn> preDrawForSession(DialysisSession session, String operator) {
        List<ConsumableTxn> result = new ArrayList<>();
        List<Consumable> all = consumableRepo.findAllByOrderByCode();
        for (Map.Entry<String, Integer> entry : STANDARD_KIT.entrySet()) {
            Consumable c = all.stream().filter(x -> x.getCode().equals(entry.getKey())).findFirst().orElse(null);
            if (c == null) continue;
            result.addAll(outboundFefo(c, TxnType.PRE_DRAW, entry.getValue(), session.getId(),
                    session.getPatient().getName(), operator,
                    "治疗单#" + session.getId() + " 标准套件预领"));
        }
        return result;
    }

    /**
     * 按 FEFO 自动挑批出库，可能跨多个批次产生多条流水。
     * 没有任何批次档案的耗材（历史数据兼容）走库存总量出库。
     */
    @Transactional
    public List<ConsumableTxn> outboundFefo(Consumable consumable, TxnType type, int qty,
                                            Long sessionId, String patientName, String operator, String note) {
        if (!OUTBOUND_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "出库类型非法：" + type);
        }
        List<ConsumableBatch> active = batchRepo
                .findByConsumableIdAndStatusOrderByExpiryDateAscIdAsc(consumable.getId(), BatchStatus.ACTIVE)
                .stream().filter(b -> b.getStockQty() > 0).toList();
        List<ConsumableTxn> result = new ArrayList<>();
        if (active.isEmpty()) {
            // 兼容无批次档案的历史耗材：直接扣总库存
            result.add(transactLegacy(consumable, type, qty, sessionId, patientName, operator, note));
            return result;
        }
        int remaining = qty;
        for (ConsumableBatch batch : active) {
            if (remaining <= 0) break;
            int take = Math.min(remaining, batch.getStockQty());
            result.add(doOutbound(consumable, batch, type, take, sessionId, patientName, operator, note));
            remaining -= take;
        }
        if (remaining > 0) {
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」可用批次库存不足：本次" + typeLabel(type)
                            + "需要 " + qty + "，缺口 " + remaining + "（召回冻结批次不可发放）", operator);
        }
        return result;
    }

    /** 指定批次出库（库房手工使用/损耗）：冻结或库存不足直接拦截 */
    @Transactional
    public ConsumableTxn outboundBatch(Consumable consumable, ConsumableBatch batch, TxnType type, int qty,
                                       Long sessionId, String patientName, String operator, String note) {
        if (!OUTBOUND_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "出库类型非法：" + type);
        }
        if (batch.getStatus() == BatchStatus.FROZEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "批号 " + batch.getBatchNo() + " 已因厂家召回冻结，停止发放，不能" + typeLabel(type));
        }
        if (batch.getStatus() == BatchStatus.RETURNED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批号 " + batch.getBatchNo() + " 已退回厂家，无库存可发");
        }
        if (batch.getStockQty() < qty) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "批号 " + batch.getBatchNo() + " 库存不足：剩余 " + batch.getStockQty());
        }
        return doOutbound(consumable, batch, type, qty, sessionId, patientName, operator, note);
    }

    private ConsumableTxn doOutbound(Consumable consumable, ConsumableBatch batch, TxnType type, int qty,
                                     Long sessionId, String patientName, String operator, String note) {
        batch.setStockQty(batch.getStockQty() - qty);
        if (batch.getStockQty() == 0 && batch.getStatus() == BatchStatus.ACTIVE) {
            batch.setStatus(BatchStatus.DEPLETED);
        }
        batchRepo.save(batch);
        consumable.setStock(consumable.getStock() - qty);
        consumableRepo.save(consumable);
        ConsumableTxn txn = writeTxn(consumable, batch, type, -qty, sessionId, patientName, operator, note);
        if (consumable.getStock() <= consumable.getSafeStock()) {
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」库存 " + consumable.getStock() + " 已低于安全库存 "
                            + consumable.getSafeStock() + "，请及时补货", operator);
        }
        return txn;
    }

    /**
     * 归还入库到指定批次（患者请假退回预领、召回换批退回）。
     * 归还到冻结批次时只增加库存、不解除冻结——该部分随后续召回退回厂家。
     */
    @Transactional
    public ConsumableTxn returnToBatch(Consumable consumable, ConsumableBatch batch, int qty,
                                       Long sessionId, String patientName, String operator, String note) {
        batch.setStockQty(batch.getStockQty() + qty);
        if (batch.getStatus() == BatchStatus.DEPLETED && batch.getRecallId() == null) {
            batch.setStatus(BatchStatus.ACTIVE);
        }
        batchRepo.save(batch);
        consumable.setStock(consumable.getStock() + qty);
        consumableRepo.save(consumable);
        return writeTxn(consumable, batch, TxnType.RETURN, qty, sessionId, patientName, operator, note);
    }

    /**
     * 召回出库：冻结批次的剩余未使用库存退回厂家，批次标记已退回（库存为 0 则标记耗尽）。
     */
    @Transactional
    public ConsumableTxn recallOut(Consumable consumable, ConsumableBatch batch, String recallNo,
                                   String operator, String note) {
        int qty = batch.getStockQty();
        if (qty <= 0) {
            batch.setStatus(BatchStatus.DEPLETED);
            batchRepo.save(batch);
            return null;
        }
        batch.setStockQty(0);
        batch.setStatus(BatchStatus.RETURNED);
        batchRepo.save(batch);
        consumable.setStock(consumable.getStock() - qty);
        consumableRepo.save(consumable);
        return writeTxn(consumable, batch, TxnType.RECALL_OUT, -qty, null, null, operator,
                "召回单 " + recallNo + " 退回厂家" + (note == null || note.isBlank() ? "" : "：" + note));
    }

    /** 无批次档案的手工出入库（历史兼容）：qty 为正数，方向由 type 决定 */
    @Transactional
    public ConsumableTxn transactUnbatched(Consumable consumable, TxnType type, int qty,
                                           Long sessionId, String patientName, String operator, String note) {
        if (OUTBOUND_TYPES.contains(type)) {
            return transactLegacy(consumable, type, qty, sessionId, patientName, operator, note);
        }
        if (type != TxnType.RETURN && type != TxnType.RESTOCK) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "出入库类型非法：" + type);
        }
        consumable.setStock(consumable.getStock() + Math.abs(qty));
        consumableRepo.save(consumable);
        return writeTxn(consumable, null, type, Math.abs(qty), sessionId, patientName, operator, note);
    }

    /** 无批次档案的历史兼容路径：直接扣/加总库存，库存不足记异常 */
    private ConsumableTxn transactLegacy(Consumable consumable, TxnType type, int qty,
                                         Long sessionId, String patientName, String operator, String note) {
        boolean outbound = OUTBOUND_TYPES.contains(type);
        int signed = outbound ? -Math.abs(qty) : Math.abs(qty);
        int newStock = consumable.getStock() + signed;
        if (newStock < 0) {
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」库存不足：当前 " + consumable.getStock()
                            + "，本次" + typeLabel(type) + "需要 " + Math.abs(qty), operator);
        }
        consumable.setStock(newStock);
        consumableRepo.save(consumable);
        ConsumableTxn txn = writeTxn(consumable, null, type, signed, sessionId, patientName, operator, note);
        if (newStock <= consumable.getSafeStock()) {
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」库存 " + newStock + " 已低于安全库存 "
                            + consumable.getSafeStock() + "，请及时补货", operator);
        }
        return txn;
    }

    private ConsumableTxn writeTxn(Consumable consumable, ConsumableBatch batch, TxnType type, int signedQty,
                                   Long sessionId, String patientName, String operator, String note) {
        ConsumableTxn txn = new ConsumableTxn();
        txn.setConsumableId(consumable.getId());
        txn.setConsumableName(consumable.getName());
        if (batch != null) {
            txn.setBatchId(batch.getId());
            txn.setBatchNo(batch.getBatchNo());
        }
        txn.setQty(signedQty);
        txn.setType(type);
        txn.setSessionId(sessionId);
        txn.setPatientName(patientName);
        txn.setOperatorName(operator);
        txn.setNote(note);
        return txnRepo.save(txn);
    }

    public static String typeLabel(TxnType type) {
        return switch (type) {
            case PRE_DRAW -> "预领";
            case USE -> "使用";
            case RETURN -> "归还";
            case WASTE -> "损耗";
            case RESTOCK -> "入库";
            case BATCH_IN -> "批次入库";
            case RECALL_OUT -> "召回出库";
        };
    }
}
