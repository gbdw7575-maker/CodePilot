package com.dsh.api;

import com.dsh.api.dto.CreateThread;
import com.dsh.api.dto.Message;
import com.dsh.api.dto.PageDto;
import com.dsh.api.dto.Thread;
import com.dsh.api.dto.ThreadPatch;
import com.dsh.application.DeletionService;
import com.dsh.application.IdempotencyService;
import com.dsh.application.ThreadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ThreadController {

    private final ThreadService threadService;
    private final DeletionService deletionService;
    private final IdempotencyService idempotency;

    public ThreadController(ThreadService threadService, DeletionService deletionService,
                            IdempotencyService idempotency) {
        this.threadService = threadService;
        this.deletionService = deletionService;
        this.idempotency = idempotency;
    }

    @GetMapping("/projects/{projectId}/threads")
    public PageDto<Thread> list(@PathVariable String projectId,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer limit) {
        Pages.Query query = Pages.parse(cursor, limit);
        List<Thread> ordered = threadService.listByProject(projectId).stream().map(ThreadService::toDto).toList();
        return Pages.slice(ordered, query);
    }

    @PostMapping("/projects/{projectId}/threads")
    public ResponseEntity<?> create(@PathVariable String projectId,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody CreateThread request) {
        return idempotency.execute(idempotencyKey, () -> {
            Thread created = threadService.create(projectId, request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .eTag(Etags.of(created.version()))
                    .body(created);
        });
    }

    @GetMapping("/threads/{threadId}")
    public ResponseEntity<Thread> get(@PathVariable String threadId) {
        Thread thread = threadService.get(threadId);
        return ResponseEntity.ok().eTag(Etags.of(thread.version())).body(thread);
    }

    @DeleteMapping("/projects/{projectId}/threads/{threadId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String threadId) {
        deletionService.deleteThread(projectId, threadId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/threads/{threadId}")
    public ResponseEntity<?> patch(@PathVariable String threadId,
                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                   @RequestBody ThreadPatch request) {
        Thread current = threadService.get(threadId);
        Etags.check(ifMatch, current.version());
        Thread updated = threadService.patch(threadId, request);
        return ResponseEntity.ok().eTag(Etags.of(updated.version())).body(updated);
    }

    @GetMapping("/threads/{threadId}/messages")
    public PageDto<Message> listMessages(@PathVariable String threadId,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        Pages.Query query = Pages.parse(cursor, limit);
        List<Message> ordered = threadService.listMessages(threadId).stream()
                .map(ThreadService::toDto)
                .toList();
        return Pages.slice(ordered, query);
    }
}
