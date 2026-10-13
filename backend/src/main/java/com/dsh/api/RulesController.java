package com.dsh.api;

import com.dsh.api.dto.RuleFile;
import com.dsh.api.dto.Rules;
import com.dsh.rules.RuleLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目规则读取 REST（契约 getRules，MVP）：返回根规则 + 全项目目录规则
 * （无用户输入上下文时等价于根规则；按目录作用域的完整视图在装配侧生效）。
 * 只读接口，不提供自动改写规则文件的路径。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/rules")
public class RulesController {

    private final RuleLoader ruleLoader;

    public RulesController(RuleLoader ruleLoader) {
        this.ruleLoader = ruleLoader;
    }

    @GetMapping
    public Rules getRules(@PathVariable String projectId) {
        RuleLoader.LoadedRules loaded = ruleLoader.load(projectId, null);
        List<RuleFile> files = loaded.files().stream()
                .map(f -> new RuleFile(f.relativePath(), f.sha256(), f.content(), f.truncated()))
                .toList();
        return new Rules(projectId, files, loaded.effectiveVersion());
    }
}
