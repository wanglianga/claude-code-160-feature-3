package com.hd.center.controller;

import com.hd.center.config.CurrentUser;
import com.hd.center.entity.Enums.FollowUpStatus;
import com.hd.center.entity.Enums.QualityVerdict;
import com.hd.center.entity.Enums.RecallStatus;
import com.hd.center.entity.Enums.ReviewResult;
import com.hd.center.entity.RecallFollowUp;
import com.hd.center.entity.RecallOrder;
import com.hd.center.service.RecallService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 耗材批次召回：发起召回/冻结、受影响治疗记录、护士回访、医生复查、换批、办结与供应商考核。
 */
@RestController
@RequestMapping("/api/recalls")
@RequiredArgsConstructor
public class RecallController {

    private final RecallService recallService;

    /** 召回单列表（可按状态过滤） */
    @GetMapping
    public List<RecallOrder> list(@RequestParam(required = false) RecallStatus status) {
        return recallService.list(status);
    }

    public record InitiateRequest(@NotNull Long consumableId, @NotNull String batchNo,
                                  @NotNull String reason, String manufacturerDocNo, String supplier) {}

    /** 发起厂家召回：冻结未使用库存并列出受影响治疗记录 */
    @PostMapping
    public Map<String, Object> initiate(@RequestBody InitiateRequest request,
                                        @AuthenticationPrincipal CurrentUser current) {
        return recallService.initiate(request.consumableId(), request.batchNo(), request.reason(),
                request.manufacturerDocNo(), request.supplier(), current.name());
    }

    /** 召回详情：召回单 + 回访单列表 + 批次流水 + 可换批次 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return recallService.detail(id);
    }

    public record FollowRequest(@NotNull FollowUpStatus status, String note, String symptoms) {}

    /** 护士电话回访：是否不适；不适自动上报异常转医生复查 */
    @PostMapping("/{id}/follow-ups/{fid}")
    public RecallFollowUp followUp(@PathVariable Long id, @PathVariable Long fid,
                                   @RequestBody FollowRequest request,
                                   @AuthenticationPrincipal CurrentUser current) {
        return recallService.followUp(id, fid, request.status(), request.note(), request.symptoms(),
                current.name());
    }

    public record ReviewRequest(@NotNull ReviewResult result, String recheckPlan, String reviewNote) {}

    /** 医生复查结论（已从治疗记录查看当日机器、护士、耗材与透析参数） */
    @PostMapping("/{id}/follow-ups/{fid}/review")
    public RecallFollowUp review(@PathVariable Long id, @PathVariable Long fid,
                                 @RequestBody ReviewRequest request,
                                 @AuthenticationPrincipal CurrentUser current) {
        return recallService.review(id, fid, request.result(), request.recheckPlan(),
                request.reviewNote(), current.name());
    }

    public record ReplaceRequest(@NotNull Long replacementBatchId) {}

    /** 为未开始的治疗单一键更换批次（召回批号退回冻结批次，新批次重新预领） */
    @PostMapping("/{id}/follow-ups/{fid}/replace")
    public RecallFollowUp replace(@PathVariable Long id, @PathVariable Long fid,
                                  @RequestBody ReplaceRequest request,
                                  @AuthenticationPrincipal CurrentUser current) {
        return recallService.replaceBatch(id, fid, request.replacementBatchId(), current.name());
    }

    public record CloseRequest(@NotNull QualityVerdict verdict, Integer deduction,
                               Boolean returnUnused, String closeNote) {}

    /** 办结召回：质量定性 + 供应商考核扣分，可将未使用库存退回厂家 */
    @PostMapping("/{id}/close")
    public RecallOrder close(@PathVariable Long id, @RequestBody CloseRequest request,
                             @AuthenticationPrincipal CurrentUser current) {
        return recallService.close(id, request.verdict(), request.deduction(),
                Boolean.TRUE.equals(request.returnUnused()), request.closeNote(), current.name());
    }

    /** 供应商考核汇总（仅统计已办结召回） */
    @GetMapping("/supplier-assessment")
    public List<Map<String, Object>> supplierAssessment() {
        return recallService.supplierAssessment();
    }
}
