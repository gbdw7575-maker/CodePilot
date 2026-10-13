package com.dsh.api;

import com.dsh.application.IdempotencyService;
import com.dsh.api.dto.ErrorCode;
import com.dsh.orchestration.acceptance.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Human local UI only. No ToolSPI accepts a policy or approves its own checks. */
@RestController @RequestMapping("/api/v1/runs/{runId}/task-acceptance-mode")
public class TaskAcceptanceModeController {
    private final RootTaskAcceptanceModes modes; private final IdempotencyService idempotency;
    public TaskAcceptanceModeController(RootTaskAcceptanceModes modes,IdempotencyService idempotency){this.modes=modes;this.idempotency=idempotency;}
    @GetMapping public ResponseEntity<?> view(@PathVariable String runId){return ResponseEntity.ok().header("Cache-Control","no-store").body(modes.view(runId));}
    @PostMapping(consumes="application/json") public ResponseEntity<?> enable(@PathVariable String runId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody byte[] raw){
        com.fasterxml.jackson.databind.JsonNode body;
        try{body=CanonicalTaskJson.parse(raw);}catch(IllegalArgumentException bad){throw invalid();}
        var fields=new java.util.HashSet<String>();if(body.isObject())body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(java.util.Set.of("mode","confirmed"))||!body.path("mode").isTextual()
                ||!RootTaskAcceptanceModes.STRONG.equals(body.get("mode").textValue())
                ||!body.path("confirmed").isBoolean()||!body.get("confirmed").booleanValue())throw invalid();
        return idempotency.execute(key,()->ResponseEntity.ok().body(modes.enable(runId)));
    }
    private static ApiException invalid(){return new ApiException(ErrorCode.VALIDATION_FAILED,"仅接受明确confirmed=true与EXECUTABLE_CHECKS，禁止降级或自定义模式");}
}
