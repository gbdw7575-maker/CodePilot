package com.dsh.api.dto;
import java.time.Instant;
public record TaskAcceptanceModeView(String rootRunId,String mode,boolean frozen,
        boolean optInAvailable,boolean executionAvailable,Instant frozenAt) { }
