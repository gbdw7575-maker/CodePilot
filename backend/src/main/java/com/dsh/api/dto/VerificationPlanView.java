package com.dsh.api.dto;

import com.dsh.application.DefectReproductionPlan;
import com.dsh.application.VerificationPlan;

/** Local-user read-only view; never executes a command or changes a Run requirement. */
public record VerificationPlanView(String command, boolean expectTests, int timeoutSeconds,
        String planVersion, String purpose, String acceptanceDescription,
        DefectReproductionPlan defectReproduction, String digest, String source,
        com.dsh.application.TestReportSpec testReports) {
    public static VerificationPlanView from(VerificationPlan plan, boolean registered) {
        return new VerificationPlanView(plan.commandSummary(), plan.expectTests(), plan.timeoutSeconds(),
                plan.planVersion(), plan.purpose(), plan.acceptanceDescription(), plan.reproduction(),
                plan.displayDigest(), registered ? "RUN_REGISTRATION" : "PROJECT_CONFIGURATION", plan.testReports());
    }
}
