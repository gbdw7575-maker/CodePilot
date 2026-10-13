package com.dsh.api;

import com.dsh.api.dto.Checkpoint;
import com.dsh.api.dto.PageDto;
import com.dsh.api.dto.RestoreRequest;
import com.dsh.api.dto.RestoreResult;
import com.dsh.agent.control.CheckpointService;
import com.dsh.application.IdempotencyService;
import com.dsh.application.TurnService;
import com.dsh.storage.CheckpointEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 检查点 REST：列表 + 恢复（不恢复执行）。 */
@RestController
@RequestMapping("/api/v1")
public class CheckpointController {

    private final CheckpointService checkpointService;
    private final TurnService turnService;
    private final IdempotencyService idempotency;

    public CheckpointController(CheckpointService checkpointService, TurnService turnService,
                                IdempotencyService idempotency) {
        this.checkpointService = checkpointService;
        this.turnService = turnService;
        this.idempotency = idempotency;
    }

    @GetMapping("/runs/{runId}/checkpoints")
    public PageDto<Checkpoint> list(@PathVariable String runId) {
        turnService.getRun(runId);
        List<CheckpointEntity> entities = checkpointService.listByRun(runId);
        List<Checkpoint> items = entities.stream().map(CheckpointService::toDto).toList();
        return new PageDto<>(items, null);
    }

    @PostMapping("/checkpoints/{checkpointId}/restore")
    public ResponseEntity<?> restore(@PathVariable String checkpointId,
                                      @RequestHeader(name = "Idempotency-Key", required = false)
                                      String idempotencyKey,
                                      @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                      @RequestBody RestoreRequest request) {
        return idempotency.execute(idempotencyKey, () -> {
            RestoreResult result = checkpointService.restore(checkpointId,
                    request.expectedWorkspaceDigest());
            return ResponseEntity.ok().body(result);
        });
    }
}
