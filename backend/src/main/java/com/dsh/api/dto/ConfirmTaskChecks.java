package com.dsh.api.dto;
import java.util.List;
/** Only a human's exact selection; no commands, source text or caller-provided binding hashes. */
public record ConfirmTaskChecks(Integer taskVersion, List<String> checkRefs, Boolean confirmed) { }
