package com.dsh.agent.tool;

import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** git_status：读取任务副本相对基线的结构化变更状态（porcelain v1，含未跟踪新文件）。 */
@Component
public class GitStatusTool implements Tool {

    @Override
    public String name() {
        return "git_status";
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) {
        CommandOutcome result = GitSupport.git(ctx, "status --porcelain=v1 --untracked-files=all", 30);
        if (result.termination() != CommandSpec.Termination.COMPLETED || result.exitCode() == null
                || result.exitCode() != 0) {
            return GitSupport.gitFailure(name(), "git_status", result);
        }

        List<String[]> entries = GitSupport.parsePorcelain(result.stdout() == null ? "" : result.stdout());
        ArrayNode changes = ctx.objectMapper().createArrayNode();
        for (String[] entry : entries) {
            ObjectNode node = ctx.objectMapper().createObjectNode();
            node.put("status", entry[0]);
            node.put("path", entry[1]);
            changes.add(node);
        }
        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("clean", changes.isEmpty());
        structured.set("changes", changes);
        String text = entries.isEmpty() ? "工作区干净（相对基线无变更）"
                : entries.stream().map(e -> e[0] + " " + e[1]).reduce((a, b) -> a + "\n" + b).orElse("");
        return ToolOutcome.succeeded(changes.isEmpty() ? "工作区干净" : changes.size() + " 项变更",
                text, structured, name());
    }
}
