package com.dsh.api;

import com.dsh.api.dto.MemoryEntryUpdate;
import com.dsh.api.dto.MemorySettingsUpdate;
import com.dsh.memory.MemoryEntry;
import com.dsh.memory.MemoryProvider;
import com.dsh.memory.MemoryView;
import com.dsh.memory.RulesView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auto Memory 管理 REST（内部契约 §18.3）：用户查看/编辑/删除/启停，项目作用域，
 * 并发用 If-Match 版本校验（缺失 428、不匹配 412）。不提供绕过权限的路径写入。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/memory")
public class MemoryController {

    private final MemoryProvider memoryProvider;

    public MemoryController(MemoryProvider memoryProvider) {
        this.memoryProvider = memoryProvider;
    }

    @GetMapping
    public ResponseEntity<MemoryView> view(@PathVariable String projectId) {
        MemoryView view = memoryProvider.view(projectId);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(view);
    }

    @GetMapping("/rules")
    public ResponseEntity<RulesView> rules(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryProvider.loadRules(projectId));
    }

    @PutMapping("/entries/{entryId}")
    public ResponseEntity<MemoryEntry> updateEntry(@PathVariable String projectId,
                                                   @PathVariable String entryId,
                                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                   @RequestBody MemoryEntryUpdate request) {
        MemoryView current = memoryProvider.view(projectId);
        Etags.check(ifMatch, current.version());
        MemoryEntry updated = memoryProvider.updateEntry(projectId, entryId,
                request.content(), request.scope(), current.version());
        return ResponseEntity.ok().eTag(Etags.of(current.version() + 1)).body(updated);
    }

    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(@PathVariable String projectId,
                                            @PathVariable String entryId,
                                            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        MemoryView current = memoryProvider.view(projectId);
        Etags.check(ifMatch, current.version());
        memoryProvider.deleteEntry(projectId, entryId, current.version());
        return ResponseEntity.noContent().eTag(Etags.of(current.version() + 1)).build();
    }

    @PutMapping("/settings")
    public ResponseEntity<MemoryView> settings(@PathVariable String projectId,
                                               @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                               @RequestBody MemorySettingsUpdate request) {
        if (request == null || request.enabled() == null) {
            throw ApiException.of(com.dsh.api.dto.ErrorCode.VALIDATION_FAILED, "enabled 必填且为布尔值");
        }
        MemoryView current = memoryProvider.view(projectId);
        Etags.check(ifMatch, current.version());
        MemoryView updated = memoryProvider.setEnabled(projectId, request.enabled(), current.version());
        return ResponseEntity.ok().eTag(Etags.of(updated.version())).body(updated);
    }
}
