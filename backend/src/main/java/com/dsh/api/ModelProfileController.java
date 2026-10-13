package com.dsh.api;

import com.dsh.api.dto.ModelProfile;
import com.dsh.api.dto.ModelProfilePatch;
import com.dsh.api.dto.ModelProfileWrite;
import com.dsh.api.dto.ModelValidation;
import com.dsh.api.dto.ModelValidationRequest;
import com.dsh.api.dto.PageDto;
import com.dsh.application.IdempotencyService;
import com.dsh.application.ModelProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/model-profiles")
public class ModelProfileController {

    private final ModelProfileService profileService;
    private final IdempotencyService idempotency;

    public ModelProfileController(ModelProfileService profileService, IdempotencyService idempotency) {
        this.profileService = profileService;
        this.idempotency = idempotency;
    }

    @GetMapping
    public PageDto<ModelProfile> list(@RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        Pages.Query query = Pages.parse(cursor, limit);
        List<ModelProfile> ordered = profileService.listOrdered();
        return Pages.slice(ordered, query);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody ModelProfileWrite request) {
        return idempotency.execute(idempotencyKey, () -> {
            ModelProfile created = profileService.create(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .eTag(Etags.of(created.version()))
                    .body(created);
        });
    }

    @GetMapping("/{modelProfileId}")
    public ResponseEntity<ModelProfile> get(@PathVariable String modelProfileId) {
        ModelProfile profile = profileService.get(modelProfileId);
        return ResponseEntity.ok().eTag(Etags.of(profile.version())).body(profile);
    }

    @PatchMapping("/{modelProfileId}")
    public ResponseEntity<?> patch(@PathVariable String modelProfileId,
                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                   @RequestBody ModelProfilePatch request) {
        ModelProfile current = profileService.get(modelProfileId);
        Etags.check(ifMatch, current.version());
        ModelProfile updated = profileService.patch(modelProfileId, request);
        return ResponseEntity.ok().eTag(Etags.of(updated.version())).body(updated);
    }

    @PostMapping("/{modelProfileId}/validate")
    public ResponseEntity<?> validate(@PathVariable String modelProfileId,
                                      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestBody ModelValidationRequest request) {
        return idempotency.execute(idempotencyKey, () -> {
            ModelValidation validation = profileService.validate(modelProfileId, request);
            return ResponseEntity.ok(validation);
        });
    }
}
