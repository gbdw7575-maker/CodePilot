package com.dsh.api;

import com.dsh.api.dto.ToolCapabilitySnapshot;
import com.dsh.application.ToolCapabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-10 工具可达性：能力清单端点。
 * 返回核心工具（始终可达）与扩展工具（按 authorized && tool_search.enabled 标记 reachable），
 * 区分 tool_search 开关两态，避免宣称不可用扩展为可用。
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilitiesController {

    private final ToolCapabilityService capabilityService;

    public CapabilitiesController(ToolCapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @GetMapping
    public ResponseEntity<ToolCapabilitySnapshot> list() {
        return ResponseEntity.ok(capabilityService.snapshot());
    }
}
