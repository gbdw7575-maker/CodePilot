package com.dsh.api;

import com.dsh.api.dto.Approval;
import com.dsh.api.dto.ApprovalDecision;
import com.dsh.api.dto.PageDto;
import com.dsh.agent.control.ApprovalService;
import com.dsh.application.IdempotencyService;
import com.dsh.application.TurnService;
import com.dsh.storage.ApprovalEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 审批 REST：列出轮次审批 + 决策后恢复执行。 */
@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final TurnService turnService;
    private final IdempotencyService idempotency;

    public ApprovalController(ApprovalService approvalService, TurnService turnService,
                              IdempotencyService idempotency) {
        this.approvalService = approvalService;
        this.turnService = turnService;
        this.idempotency = idempotency;
    }

    @GetMapping("/turns/{turnId}/approvals")
    public PageDto<Approval> list(@PathVariable String turnId) {
        turnService.load(turnId);
        List<ApprovalEntity> entities = approvalService.listByTurn(turnId);
        List<Approval> items = entities.stream().map(ApprovalService::toDto).toList();
        return new PageDto<>(items, null);
    }

    @PostMapping("/approvals/{approvalId}/decision")
    public ResponseEntity<?> decide(@PathVariable String approvalId,
                                    @RequestHeader(name = "Idempotency-Key", required = false)
                                    String idempotencyKey,
                                    @RequestBody ApprovalDecision request) {
        return idempotency.execute(idempotencyKey, () -> {
            ApprovalEntity entity = approvalService.decide(approvalId,
                    request.decision(), request.parametersDigest(), request.workspaceDigest());
            Approval dto = ApprovalService.toDto(entity);

            // APPROVED → 恢复执行待审批工具
            if ("APPROVED".equals(entity.getStatus())) {
                turnService.resumeFromApproval(entity.getTurnId());
            }
            return ResponseEntity.ok().eTag(Etags.of(dto.version())).body(dto);
        });
    }
}
