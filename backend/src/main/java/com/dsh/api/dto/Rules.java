package com.dsh.api.dto;

import java.util.List;

/** 契约 Rules：项目适用规则集合与整体版本（任一文件内容/顺序变化都会改变 effectiveVersion）。 */
public record Rules(String projectId, List<RuleFile> files, String effectiveVersion) {
}
