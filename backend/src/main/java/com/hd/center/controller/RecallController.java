package com.hd.center.controller;

import com.hd.center.config.CurrentUser;
import com.hd.center.entity.Enums.FollowupStatus;
import com.hd.center.entity.Enums.RecallOutcome;
import com.hd.center.entity.RecallFollowup;
import com.hd.center.service.RecallService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 耗材批次召回：发起召回、患者回访、医生复查、更换批次、关闭与供应商考核。
 */
@RestController
@RequestMapping("/api/recalls")
@RequiredArgsConstructor
public class RecallController {

    private final RecallService recallService;

    /** 召回单列表 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return recallService.list();
    }

    public record CreateRequest(@NotNull Long batchId, @NotBlank String reason) {}

    /** 发起召回：冻结批次库存，按出库/使用记录生成受影响治疗的回访任务 */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest request,
                                      @AuthenticationPrincipal CurrentUser current) {
        return recallService.create(request.batchId(), request.reason(), current.name());
    }

    /** 召回详情：受影响治疗回访、待更换批次治疗单、统计 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return recallService.detail(id);
    }

    public record FollowupRequest(@NotNull FollowupStatus status, Boolean discomfort,
                                  String symptoms, String note) {}

    /** 护士回访登记 */
    @PutMapping("/followups/{fid}")
    public RecallFollowup followup(@PathVariable Long fid, @RequestBody FollowupRequest request,
                                   @AuthenticationPrincipal CurrentUser current) {
        return recallService.followup(fid, request.status(), request.discomfort(),
                request.symptoms(), request.note(), current.name());
    }

    public record ReviewRequest(@NotNull Boolean reviewNeeded, String reviewNote) {}

    /** 医生复查评估 */
    @PutMapping("/followups/{fid}/review")
    public RecallFollowup review(@PathVariable Long fid, @RequestBody ReviewRequest request,
                                 @AuthenticationPrincipal CurrentUser current) {
        return recallService.review(fid, request.reviewNeeded(), request.reviewNote(), current.name());
    }

    /** 为一条治疗单更换批次（召回批次退回冻结库存，重新预领正常批次） */
    @PostMapping("/{id}/replace/{sessionId}")
    public Map<String, Object> replace(@PathVariable Long id, @PathVariable Long sessionId,
                                       @AuthenticationPrincipal CurrentUser current) {
        return recallService.replaceBatch(id, sessionId, current.name());
    }

    /** 一键为全部待更换治疗单更换批次 */
    @PostMapping("/{id}/replace-all")
    public Map<String, Object> replaceAll(@PathVariable Long id,
                                          @AuthenticationPrincipal CurrentUser current) {
        return recallService.replaceAll(id, current.name());
    }

    public record CloseRequest(@NotNull RecallOutcome outcome, String conclusion) {}

    /** 关闭召回：确认召回（剩余库存退回厂家）或解除冻结；结果进入供应商考核 */
    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable Long id, @RequestBody CloseRequest request,
                                     @AuthenticationPrincipal CurrentUser current) {
        return recallService.close(id, request.outcome(), request.conclusion(), current.name());
    }

    /** 供应商耗材质量考核 */
    @GetMapping("/supplier-stats")
    public List<Map<String, Object>> supplierStats() {
        return recallService.supplierStats();
    }
}
