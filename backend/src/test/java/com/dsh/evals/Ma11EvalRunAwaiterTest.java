package com.dsh.evals;
import com.dsh.agent.control.*;
import com.dsh.api.dto.ApprovalDecision;
import com.dsh.api.dto.RunState;
import com.dsh.api.dto.TurnAccepted;
import com.dsh.application.TurnService;
import com.dsh.orchestration.task.*;
import com.dsh.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ma11EvalRunAwaiterTest {
    final RunRepository runs = mock(RunRepository.class);
    final ApprovalRepository approvals = mock(ApprovalRepository.class);
    final ApprovalService decisions = mock(ApprovalService.class);
    final TurnService turns = mock(TurnService.class);
    final RunControlService control = mock(RunControlService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<TaskQueryService> tasks = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") final ObjectProvider<TaskCancelService> cancels = mock(ObjectProvider.class);
    final EvalRunAwaiter waiter = new EvalRunAwaiter(runs, approvals, decisions, turns, control, tasks, cancels);
    final TurnAccepted accepted = new TurnAccepted("turn", "root", RunState.QUEUED, "0");
    RunEntity run(String id, String state) {
        var now = java.time.Instant.now();
        return new RunEntity(id, "turn", null, "MAIN", state, "PENDING", 1,
                "ws", "digest", 10, 10, 10000, now.plusSeconds(60), now, now);
    }
    RunEntity await() { return waiter.await(accepted, System.nanoTime(), Duration.ofSeconds(2), Duration.ZERO, 1); }
    @Test void parkedRootIsNotSampledBeforeCompletion() {
        var active=run("root","RUNNING"); var done=run("root","COMPLETED");
        when(runs.findById("root")).thenReturn(Optional.of(active),Optional.of(done));
        assertSame(done,await()); verify(runs,times(2)).findById("root"); verifyNoInteractions(control);
    }
    @Test void terminalRootStillWaitsForChildAndTaskSettlement() {
        var done=run("root","COMPLETED"); when(runs.findById("root")).thenReturn(Optional.of(done));
        when(runs.listChildren("root")).thenReturn(List.of(run("child","RUNNING")),List.of(run("child","COMPLETED")),List.of(run("child","COMPLETED")));
        var query=mock(TaskQueryService.class);when(tasks.getIfAvailable()).thenReturn(query);
        var active=mock(TaskEntity.class);when(active.getState()).thenReturn("VERIFYING");
        var settled=mock(TaskEntity.class);when(settled.getState()).thenReturn("SUCCEEDED");
        when(query.indexRootTasks("root")).thenReturn(Map.of("A",active),Map.of("A",active),Map.of("A",settled));
        assertSame(done,await());verify(runs,times(3)).findById("root");
    }
    @Test void rootApprovalIsConsumedOnceAcrossStaleReads() {
        var waiting=run("root","WAITING_APPROVAL");var done=run("root","COMPLETED");
        when(runs.findById("root")).thenReturn(Optional.of(waiting),Optional.of(waiting),Optional.of(done));
        var approval=mock(ApprovalEntity.class);when(approval.getId()).thenReturn("approval");
        when(approvals.findByRunIdAndStatus("root","PENDING")).thenReturn(List.of(approval));
        assertSame(done,await()); verify(turns,times(1)).resumeFromApproval("turn");
        verify(decisions,times(1)).decide("approval",ApprovalDecision.Decision.APPROVE,null,null);
    }
    @Test void timeoutRequestsCancellationButNeverFabricatesTerminalResult() {
        when(runs.findById("root")).thenReturn(Optional.of(run("root","RUNNING")));
        when(runs.listChildren("root")).thenReturn(List.of(run("child","RUNNING")));
        var query=mock(TaskQueryService.class);var cancel=mock(TaskCancelService.class);
        when(tasks.getIfAvailable()).thenReturn(query);when(cancels.getIfAvailable()).thenReturn(cancel);
        var task=mock(TaskEntity.class);when(task.getState()).thenReturn("RUNNING");when(query.indexRootTasks("root")).thenReturn(Map.of("A",task));
        var ex=assertThrows(IllegalStateException.class,()->waiter.await(accepted,System.nanoTime(),Duration.ZERO,Duration.ZERO,1));
        assertTrue(ex.getMessage().contains("cleanup")); verify(control).cancel("root","EVAL_DEADLINE_EXCEEDED");
        verify(control).cancel("child","EVAL_DEADLINE_EXCEEDED");verify(cancel).cancel("root",List.of("A"),"EVAL_DEADLINE_EXCEEDED");
    }
    @Test void childApprovalUsesDecisionServiceAndWaitsForSettlement() {
        var active=run("root","RUNNING"); var done=run("root","COMPLETED");
        when(runs.findById("root")).thenReturn(Optional.of(active),Optional.of(active),Optional.of(done));
        when(runs.listChildren("root")).thenReturn(List.of(run("child","WAITING_APPROVAL")),
                List.of(run("child","WAITING_APPROVAL")), List.of(run("child","COMPLETED")));
        var approval=mock(ApprovalEntity.class); when(approval.getId()).thenReturn("child-approval");
        when(approvals.findByRunIdAndStatus("child","PENDING")).thenReturn(List.of(approval));
        assertSame(done, await());
        verify(decisions,times(1)).decide("child-approval",ApprovalDecision.Decision.APPROVE,null,null);
        verify(turns,times(1)).resumeFromApproval("turn");
        verifyNoInteractions(control);
    }
    @Test void interruptionPreservesFlagAndRequestsCancellation() {
        when(runs.findById("root")).thenReturn(Optional.of(run("root","RUNNING")));
        Thread.currentThread().interrupt();
        try {assertThrows(IllegalStateException.class,this::await);assertTrue(Thread.currentThread().isInterrupted());
            verify(control).cancel("root","EVAL_INTERRUPTED");
        } finally {Thread.interrupted();}
    }
    @Test void lateCompletionAfterTimeoutDoesNotTurnAbortedTrialIntoSuccess() {
        var active = run("root", "RUNNING");
        var late = run("root", "COMPLETED");
        when(runs.findById("root")).thenReturn(Optional.of(active), Optional.of(late));
        var error = assertThrows(IllegalStateException.class, () -> waiter.await(
                accepted, System.nanoTime(), Duration.ZERO, Duration.ofSeconds(1), 1));
        assertTrue(error.getMessage().contains("EVAL_DEADLINE_EXCEEDED"));
        verify(control).cancel("root", "EVAL_DEADLINE_EXCEEDED");
    }
}
