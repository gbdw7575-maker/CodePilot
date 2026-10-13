package com.dsh.api;

import com.dsh.api.dto.CreateProject;
import com.dsh.api.dto.PageDto;
import com.dsh.api.dto.Project;
import com.dsh.api.dto.TrustRequest;
import com.dsh.application.DeletionService;
import com.dsh.application.IdempotencyService;
import com.dsh.application.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final DeletionService deletionService;
    private final IdempotencyService idempotency;

    public ProjectController(ProjectService projectService, DeletionService deletionService,
                             IdempotencyService idempotency) {
        this.projectService = projectService;
        this.deletionService = deletionService;
        this.idempotency = idempotency;
    }

    @GetMapping
    public PageDto<Project> list(@RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit) {
        Pages.Query query = Pages.parse(cursor, limit);
        List<Project> ordered = projectService.listOrdered().stream().map(ProjectService::toDto).toList();
        return Pages.slice(ordered, query);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody CreateProject request) {
        return idempotency.execute(idempotencyKey, () -> {
            Project created = projectService.create(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .eTag(Etags.of(created.version()))
                    .body(created);
        });
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<Project> get(@PathVariable String projectId) {
        Project project = projectService.get(projectId);
        return ResponseEntity.ok().eTag(Etags.of(project.version())).body(project);
    }

    @PutMapping("/{projectId}/trust")
    public ResponseEntity<?> trust(@PathVariable String projectId,
                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                   @RequestBody TrustRequest request) {
        if (request == null || request.trusted() == null) {
            throw ApiException.of(com.dsh.api.dto.ErrorCode.VALIDATION_FAILED, "trusted 必填且为布尔值");
        }
        Project current = projectService.get(projectId);
        Etags.check(ifMatch, current.version());
        Project updated = projectService.trust(projectId, request.trusted());
        return ResponseEntity.ok().eTag(Etags.of(updated.version())).body(updated);
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId) {
        deletionService.deleteProject(projectId);
        return ResponseEntity.noContent().build();
    }
}
