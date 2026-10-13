package com.dsh.api;

import com.dsh.api.dto.PlanView;
import com.dsh.application.TurnService;
import com.dsh.orchestration.plan.PlanViewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MA-10 计划与任务只读快照（规范 §11 UI 最低交付：主计划/任务依赖/子任务状态/证据）。
 *
 * <p>无条件装配：编排开关关闭时仍可读取已有计划与结果（规范 §12）。run 不存在返回 404；
 * run 存在但从未产生计划时返回空列表（200），前端据此区分"无计划"与"运行不存在"。</p>
 */
@RestController
@RequestMapping("/api/v1/runs")
public class PlanController {

    private final TurnService turnService;
    private final PlanViewService planViewService;

    public PlanController(TurnService turnService, PlanViewService planViewService) {
        this.turnService = turnService;
        this.planViewService = planViewService;
    }

    /** 根 Run 全部计划修订（最新在前），内嵌任务定义、权威状态与最新结果证据。 */
    @GetMapping("/{runId}/plans")
    public PlanViewList plans(@PathVariable String runId) {
        turnService.getRun(runId);
        return new PlanViewList(planViewService.listPlans(runId));
    }

    /** 列表包装（非分页：单计划任务数 ≤12，修订数有界；字段对齐契约 PlanViewList）。 */
    public record PlanViewList(List<PlanView> plans) {
    }
}
