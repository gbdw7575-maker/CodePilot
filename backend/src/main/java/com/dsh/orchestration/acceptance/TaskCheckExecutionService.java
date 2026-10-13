package com.dsh.orchestration.acceptance;

import com.dsh.sandbox.SandboxRunner;
import com.dsh.sandbox.TaskCheckExecutionOutcome;
import org.springframework.stereotype.Service;
import java.util.function.BooleanSupplier;

/** Internal, unused by dispatch/controllers/tools until attempt authority and final CAS exist. */
@Service
public class TaskCheckExecutionService {
    private final TaskCheckExecutionRequestFactory requests;
    private final SandboxRunner runner;
    public TaskCheckExecutionService(TaskCheckExecutionRequestFactory requests,SandboxRunner runner){
        this.requests=requests;this.runner=runner;
    }
    public TaskCheckExecutionOutcome execute(TaskCheckExecutionRequest request,BooleanSupplier cancelled){
        requests.requireCurrent(request);
        var result=runner.executeCheck(request,cancelled);
        // A changed live source/cache/registration is never accepted as current evidence.
        // This is an observation, not the future atomic task success commit.
        if(result.termination()==TaskCheckExecutionOutcome.Termination.COMPLETED) {
            try{requests.requireCurrent(request);}catch(RuntimeException unavailable){return result.staleInputs();}
        }
        return result;
    }
}
