package com.dsh.api;
import com.dsh.api.dto.ConfirmTaskChecks;
import com.dsh.application.IdempotencyService;
import com.dsh.orchestration.acceptance.TaskCheckConfirmationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/runs/{runId}/plans/{planId}/tasks/{taskId}")
public class TaskCheckRegistrationController {
    private final TaskCheckConfirmationService confirmations;private final IdempotencyService idempotency;
    public TaskCheckRegistrationController(TaskCheckConfirmationService confirmations,IdempotencyService idempotency){this.confirmations=confirmations;this.idempotency=idempotency;}
    @PostMapping(value="/acceptance-registration",consumes="application/json")
    public ResponseEntity<?> confirm(@PathVariable String runId,@PathVariable String planId,@PathVariable String taskId,
            @RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody byte[] raw){
        com.fasterxml.jackson.databind.JsonNode body;
        try{body=com.dsh.orchestration.acceptance.CanonicalTaskJson.parse(raw);}
        catch(IllegalArgumentException invalid){throw new ApiException(com.dsh.api.dto.ErrorCode.VALIDATION_FAILED,"确认请求必须是严格单JSON对象");}
        var fields=new java.util.HashSet<String>();if(body.isObject())body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(java.util.Set.of("taskVersion","checkRefs","confirmed"))||!body.path("taskVersion").isIntegralNumber()
                ||!body.path("taskVersion").canConvertToInt()||!body.path("confirmed").isBoolean()||!body.path("checkRefs").isArray())
            throw new ApiException(com.dsh.api.dto.ErrorCode.VALIDATION_FAILED,"确认请求必须精确包含taskVersion整数/checkRefs字符串数组/confirmed布尔值");
        var refs=new java.util.ArrayList<String>();for(var ref:body.path("checkRefs")){
            if(!ref.isTextual())throw new ApiException(com.dsh.api.dto.ErrorCode.VALIDATION_FAILED,"检查引用必须是字符串");refs.add(ref.textValue());}
        var request=new ConfirmTaskChecks(body.get("taskVersion").intValue(),refs,body.get("confirmed").booleanValue());
        return idempotency.execute(key,()->{var saved=confirmations.confirm(runId,planId,taskId,ifMatch,request);
            return ResponseEntity.ok().eTag("\"task-check-registration-v"+(saved.executionSpecDigest()==null?"1":"2")+"-"+saved.snapshotDigest()+"\"").body(saved);});
    }
}
