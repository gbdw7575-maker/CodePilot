package com.dsh.api;

import com.dsh.api.dto.Diff;
import com.dsh.api.dto.PageDto;
import com.dsh.api.dto.Run;
import com.dsh.api.dto.ToolDefinitionDto;
import com.dsh.agent.control.RunControlService;
import com.dsh.application.IdempotencyService;
import com.dsh.application.ToolCapabilityService;
import com.dsh.application.TurnService;
import com.dsh.application.WorkspaceDiffService;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final TurnService turnService;
    private final RunControlService runControlService;
    private final IdempotencyService idempotency;
    private final WorkspaceDiffService workspaceDiffService;
    private final ToolCapabilityService toolCapabilityService;
    private final RunRepository runRepository;

    public RunController(TurnService turnService, RunControlService runControlService,
                         IdempotencyService idempotency, WorkspaceDiffService workspaceDiffService,
                         ToolCapabilityService toolCapabilityService,
                         RunRepository runRepository) {
        this.turnService = turnService;
        this.runControlService = runControlService;
        this.idempotency = idempotency;
        this.workspaceDiffService = workspaceDiffService;
        this.toolCapabilityService = toolCapabilityService;
        this.runRepository = runRepository;
    }

    @GetMapping("/{runId}")
    public ResponseEntity<Run> get(@PathVariable String runId) {
        Run run = turnService.getRun(runId);
        return ResponseEntity.ok().eTag(Etags.of(run.version())).body(run);
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String runId,
                                   @RequestHeader(name = "Idempotency-Key", required = false)
                                   String idempotencyKey) {
        return idempotency.execute(idempotencyKey, () -> {
            RunEntity entity = runControlService.cancel(runId, "USER_CANCELLED");
            Run run = TurnService.toRunDto(entity);
            HttpStatus status = TERMINAL.contains(entity.getState())
                    ? HttpStatus.OK : HttpStatus.ACCEPTED;
            return ResponseEntity.status(status).eTag(Etags.of(run.version())).body(run);
        });
    }

    @PostMapping("/{runId}/resume")
    public ResponseEntity<?> resume(@PathVariable String runId,
                                    @RequestHeader(name = "Idempotency-Key", required = false)
                                    String idempotencyKey,
                                    @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return idempotency.execute(idempotencyKey, () -> {
            var current=turnService.getRun(runId);
            if("BLOCKED".equals(current.state())&&"RECOVERY_REQUIRED".equals(current.stopReason())){
                RunEntity recovered=runControlService.recoverChecks(runId);
                Run dto=TurnService.toRunDto(recovered);
                return ResponseEntity.status(HttpStatus.ACCEPTED).eTag(Etags.of(dto.version())).body(dto);
            }
            RunEntity entity = runControlService.resume(runId);
            Run run = TurnService.toRunDto(entity);
            return ResponseEntity.status(HttpStatus.ACCEPTED).eTag(Etags.of(run.version())).body(run);
        });
    }

    @GetMapping("/{runId}/diff")
    public ResponseEntity<Diff> diff(@PathVariable String runId) {
        turnService.getRun(runId);
        Diff diff = workspaceDiffService.compute(runId);
        return ResponseEntity.ok(diff);
    }

    /**
     * MA-10 直属子运行列表（含子预算分账）。数量受 worker 并发与根预算有界，一次返回；
     * nextCursor 恒为 null（不分页）。开关关闭时历史子 Run 仍可读（规范 §12）。
     */
    @GetMapping("/{runId}/children")
    public PageDto<Run> children(@PathVariable String runId) {
        turnService.getRun(runId);
        List<Run> items = runRepository.listChildren(runId).stream()
                .map(TurnService::toRunDto)
                .toList();
        return new PageDto<>(items, null);
    }

    /** D-10 工具可达性：本 Run 模型可见工具定义分页（核心 + 已挂载授权扩展）。 */
    @GetMapping("/{runId}/tools")
    public ResponseEntity<PageDto<ToolDefinitionDto>> tools(@PathVariable String runId,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) Integer limit) {
        turnService.getRun(runId);
        return ResponseEntity.ok(toolCapabilityService.runToolsPage(runId, cursor, limit));
    }
}
