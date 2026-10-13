package com.dsh.api;

import com.dsh.api.dto.InputAccepted;
import com.dsh.api.dto.SubmitTurn;
import com.dsh.api.dto.Turn;
import com.dsh.api.dto.TurnAccepted;
import com.dsh.application.IdempotencyService;
import com.dsh.application.TurnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TurnController {

    private final TurnService turnService;
    private final IdempotencyService idempotency;

    public TurnController(TurnService turnService, IdempotencyService idempotency) {
        this.turnService = turnService;
        this.idempotency = idempotency;
    }

    @PostMapping("/threads/{threadId}/turns")
    public ResponseEntity<?> submit(@PathVariable String threadId,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody SubmitTurn request) {
        return idempotency.execute(idempotencyKey, () ->
                ResponseEntity.status(HttpStatus.ACCEPTED).body(turnService.submit(threadId, request)));
    }

    @GetMapping("/turns/{turnId}")
    public ResponseEntity<Turn> get(@PathVariable String turnId) {
        Turn turn = turnService.get(turnId);
        return ResponseEntity.ok().eTag(Etags.of(turn.version())).body(turn);
    }

    @PostMapping("/turns/{turnId}/inputs")
    public ResponseEntity<?> appendInput(@PathVariable String turnId,
                                         @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                         @RequestBody SubmitTurn request) {
        return idempotency.execute(idempotencyKey, () ->
                ResponseEntity.status(HttpStatus.ACCEPTED).body(turnService.appendInput(turnId, request)));
    }
}
