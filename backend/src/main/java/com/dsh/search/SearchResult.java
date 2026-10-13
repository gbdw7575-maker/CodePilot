package com.dsh.search;

import java.util.List;

/**
 * 一次代码搜索结果（D-05）。
 * errorCode 非 null 表示搜索本身失败（由工具映射为 ToolOutcome.failed）；
 * truncated 表示结果数或输出量超过上限被截断；零命中是成功且 matches 为空。
 */
public record SearchResult(List<CodeMatch> matches, boolean truncated, boolean timedOut,
                          String errorCode, String errorMessage) {

    public static SearchResult ok(List<CodeMatch> matches, boolean truncated) {
        return new SearchResult(List.copyOf(matches), truncated, false, null, null);
    }

    public static SearchResult error(String errorCode, String errorMessage) {
        return new SearchResult(List.of(), false, false, errorCode, errorMessage);
    }

    public static SearchResult timeout() {
        return new SearchResult(List.of(), true, true, "SEARCH_TIMEOUT", "代码搜索超时");
    }
}
