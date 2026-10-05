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
 * 耗材库存与流水。出库（预领/使用/损耗/退回厂家）为负数量，入库（归还/补货）为正数量。
 * 批次追溯：出库默认按「正常批次 + 最早失效期先出（FEFO）」自动分配批次；
 * 冻结/召回批次禁止发放；批次剩余量 = 该批次全部流水数量之和。
 */
@Service
@RequiredArgsConstructor
public class ConsumableService {

    private final ConsumableRepository consumableRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ConsumableBatchRepository batchRepo;
    private final ExceptionService exceptionService;

    /** 单次透析标准预领套件：耗材编码 -> 数量 */
    public static final Map<String, Integer> STANDARD_KIT = Map.of(
            "DIALYZER", 1,
            "BLOODLINE", 1,
            "NEEDLE", 2,
            "HEPARIN", 1,
            "SALINE", 1,
            "DIALYSATE", 1);

    public static boolean isOutbound(TxnType type) {
        return type == TxnType.PRE_DRAW || type == TxnType.USE || type == TxnType.WASTE
                || type == TxnType.SUPPLIER_RETURN;
    }

    /** 批次剩余量：该批次全部流水数量之和 */
    public int batchRemaining(Long batchId) {
        return txnRepo.findByBatchId(batchId).stream().mapToInt(ConsumableTxn::getQty).sum();
    }

    /** 耗材被冻结/召回批次占用的库存量 */
    public int frozenQty(Long consumableId) {
        return batchRepo.findByConsumableIdOrderByExpiryDateAsc(consumableId).stream()
                .filter(b -> b.getStatus() != BatchStatus.NORMAL)
                .mapToInt(b -> Math.max(0, batchRemaining(b.getId())))
                .sum();
    }

    /**
     * 记录一条耗材流水并更新库存（自动分配批次，兼容无批次耗材）。
     *
     * @param qty 正数，方向由 type 决定
     */
    @Transactional
    public ConsumableTxn transact(Consumable consumable, TxnType type, int qty,
                                  Long sessionId, String patientName, String operator, String note) {
        return transactBatch(consumable, type, qty, sessionId, patientName, operator, note, null).get(0);
    }

    /**
     * 批次感知的出入库。
     * <ul>
     *   <li>出库指定批次：批次必须正常且剩余充足，冻结/召回批次一律拒绝发放；</li>
     *   <li>出库未指定批次：按 FEFO 从正常批次自动分配（可跨批次拆分）；</li>
     *   <li>正常批次不足但存在冻结/召回库存：拒绝发放（召回期间库房不得继续发放相关耗材）；</li>
     *   <li>无任何批次可用（历史无批次耗材）：按旧逻辑记录无批次流水，库存可为负并上报异常；</li>
     *   <li>退回厂家（SUPPLIER_RETURN）只允许从指定批次出库，用于召回关闭。</li>
     * </ul>
     *
     * @return 实际写入的流水（跨批次拆分时多条）
     */
    @Transactional
    public List<ConsumableTxn> transactBatch(Consumable consumable, TxnType type, int qty,
                                             Long sessionId, String patientName, String operator,
                                             String note, Long batchId) {
        if (qty <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数量必须为正数");
        }
        boolean outbound = isOutbound(type);
        List<ConsumableTxn> saved = new ArrayList<>();

        if (outbound && type != TxnType.SUPPLIER_RETURN) {
            List<long[]> allocation;
            if (batchId != null) {
                ConsumableBatch batch = getBatch(batchId);
                if (!batch.getConsumable().getId().equals(consumable.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次与耗材不匹配");
                }
                if (batch.getStatus() != BatchStatus.NORMAL) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "批次「" + batch.getBatchNo() + "」已" + batchStatusLabel(batch.getStatus())
                                    + "，召回处理期间禁止发放");
                }
                int remaining = batchRemaining(batch.getId());
                if (remaining < qty) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "批次「" + batch.getBatchNo() + "」剩余 " + remaining + "，不足 " + qty);
                }
                allocation = List.of(new long[]{batch.getId(), qty});
            } else {
                allocation = allocate(consumable.getId(), qty);
            }
            if (allocation.stream().mapToLong(a -> a[1]).sum() >= qty) {
                for (long[] a : allocation) {
                    saved.add(saveTxn(consumable, type, (int) a[1], sessionId, patientName, operator, note,
                            batchRepo.findById(a[0]).orElse(null)));
                }
            } else {
                // 正常批次不足：若存在冻结/召回库存则阻断发放，否则按无批次旧逻辑记录
                int frozen = frozenQty(consumable.getId());
                if (frozen > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "耗材「" + consumable.getName() + "」可用批次库存不足：" + frozen
                                    + " " + (consumable.getUnit() == null ? "" : consumable.getUnit())
                                    + " 因召回冻结/召回不可发放，请先处理召回或补货");
                }
                saved.add(saveTxn(consumable, type, qty, sessionId, patientName, operator, note, null));
            }
        } else {
            // 入库（归还/补货）与退回厂家：批次可选（退回厂家必须指定）
            ConsumableBatch batch = null;
            if (batchId != null) {
                batch = getBatch(batchId);
                if (!batch.getConsumable().getId().equals(consumable.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次与耗材不匹配");
                }
            } else if (type == TxnType.SUPPLIER_RETURN) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退回厂家必须指定批次");
            }
            saved.add(saveTxn(consumable, type, qty, sessionId, patientName, operator, note, batch));
        }

        applyStock(consumable, type, qty, operator);
        return saved;
    }

    /** 入库：按批号查找或创建批次，累计入库量并记录流水 */
    @Transactional
    public ConsumableTxn restock(Consumable consumable, int qty, String batchNo, String supplier,
                                 LocalDate expiryDate, String operator, String note) {
        ConsumableBatch batch = null;
        if (batchNo != null && !batchNo.isBlank()) {
            batch = batchRepo.findByConsumableIdAndBatchNo(consumable.getId(), batchNo.trim())
                    .orElseGet(() -> {
                        ConsumableBatch b = new ConsumableBatch();
                        b.setConsumable(consumable);
                        b.setBatchNo(batchNo.trim());
                        b.setSupplier(supplier == null || supplier.isBlank() ? "未登记供应商" : supplier.trim());
                        b.setExpiryDate(expiryDate);
                        b.setReceivedQty(0);
                        return b;
                    });
            batch.setReceivedQty(batch.getReceivedQty() + qty);
            batch = batchRepo.save(batch);
        }
        return transactBatch(consumable, TxnType.RESTOCK, qty, null, null, operator,
                note == null || note.isBlank() ? "入库" + (batch != null ? "（批次 " + batch.getBatchNo() + "）" : "") : note,
                batch != null ? batch.getId() : null).get(0);
    }

    /** 为一条治疗单预领标准套件；某耗材无可用批次时跳过并上报异常，不中断排班 */
    @Transactional
    public List<ConsumableTxn> preDrawForSession(DialysisSession session, String operator) {
        List<ConsumableTxn> result = new ArrayList<>();
        List<Consumable> all = consumableRepo.findAllByOrderByCode();
        for (Map.Entry<String, Integer> entry : STANDARD_KIT.entrySet()) {
            Consumable c = all.stream().filter(x -> x.getCode().equals(entry.getKey())).findFirst().orElse(null);
            if (c == null) continue;
            try {
                result.addAll(transactBatch(c, TxnType.PRE_DRAW, entry.getValue(), session.getId(),
                        session.getPatient().getName(), operator, "治疗单#" + session.getId() + " 标准套件预领", null));
            } catch (ResponseStatusException ex) {
                exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, session.getPatient(), session,
                        session.getMachine(), "预领失败：" + ex.getReason(), operator);
            }
        }
        return result;
    }

    /** FEFO 分配：正常批次按失效期升序，剩余量充足者先；可跨批次拆分。无法足额分配时返回已分配部分 */
    private List<long[]> allocate(Long consumableId, int qty) {
        List<long[]> allocation = new ArrayList<>();
        int remaining = qty;
        List<ConsumableBatch> batches = new ArrayList<>(
                batchRepo.findByConsumableIdAndStatusOrderByExpiryDateAsc(consumableId, BatchStatus.NORMAL));
        // 失效期为空的排最后
        batches.sort((a, b) -> {
            if (a.getExpiryDate() == null && b.getExpiryDate() == null) return 0;
            if (a.getExpiryDate() == null) return 1;
            if (b.getExpiryDate() == null) return -1;
            return a.getExpiryDate().compareTo(b.getExpiryDate());
        });
        for (ConsumableBatch b : batches) {
            if (remaining <= 0) break;
            int available = batchRemaining(b.getId());
            if (available <= 0) continue;
            int take = Math.min(available, remaining);
            allocation.add(new long[]{b.getId(), take});
            remaining -= take;
        }
        return allocation;
    }

    private ConsumableTxn saveTxn(Consumable consumable, TxnType type, int qty,
                                  Long sessionId, String patientName, String operator, String note,
                                  ConsumableBatch batch) {
        int signed = isOutbound(type) ? -Math.abs(qty) : Math.abs(qty);
        ConsumableTxn txn = new ConsumableTxn();
        txn.setConsumableId(consumable.getId());
        txn.setConsumableName(consumable.getName());
        txn.setQty(signed);
        txn.setType(type);
        txn.setSessionId(sessionId);
        txn.setPatientName(patientName);
        txn.setOperatorName(operator);
        txn.setNote(note);
        if (batch != null) {
            txn.setBatchId(batch.getId());
            txn.setBatchNo(batch.getBatchNo());
        }
        return txnRepo.save(txn);
    }

    /** 汇总更新库存并上报缺货/低库存异常（一次出入库调用只更新一次） */
    private void applyStock(Consumable consumable, TxnType type, int qty, String operator) {
        int signed = isOutbound(type) ? -Math.abs(qty) : Math.abs(qty);
        int newStock = consumable.getStock() + signed;
        if (newStock < 0) {
            // 库存不足：仍然记录并产生异常，便于工程/库管补货
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」库存不足：当前 " + consumable.getStock()
                            + "，本次" + typeLabel(type) + "需要 " + Math.abs(qty), operator);
        }
        consumable.setStock(newStock);
        consumableRepo.save(consumable);
        if (newStock <= consumable.getSafeStock()) {
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」库存 " + newStock + " 已低于安全库存 "
                            + consumable.getSafeStock() + "，请及时补货", operator);
        }
    }

    private ConsumableBatch getBatch(Long id) {
        return batchRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在"));
    }

    public static String batchStatusLabel(BatchStatus status) {
        return switch (status) {
            case NORMAL -> "正常";
            case FROZEN -> "冻结";
            case RECALLED -> "召回";
        };
    }

    public static String typeLabel(TxnType type) {
        return switch (type) {
            case PRE_DRAW -> "预领";
            case USE -> "使用";
            case RETURN -> "归还";
            case WASTE -> "损耗";
            case RESTOCK -> "入库";
            case SUPPLIER_RETURN -> "退回厂家";
        };
    }
}
