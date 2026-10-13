package com.dsh.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dsh.workspace.WorkspaceLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-18 验收：read_file 已读去重（通用能力）。
 * 不依赖 Spring/Docker：直接用指向临时目录的 WorkspaceLease 直调工具。
 * 覆盖：首次正文、重复读引用视图、forceFull 取回正文、新行区间、部分重叠、
 * 子区间命中、文件变更失效、Run 隔离、相邻区间合并。
 */
class ReadFileDedupDsh18Test {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunReadRegistry registry = new RunReadRegistry();
    private ReadFileTool tool;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        tool = new ReadFileTool(registry);
        file = dir.resolve("sample.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("UNIQUE_BODY_LINE_").append(i).append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    private ToolExecutionContext ctx(String runId) {
        WorkspaceLease lease = new WorkspaceLease("ws-" + runId, runId, "proj",
                dir, dir, dir, "base", Instant.now());
        return new ToolExecutionContext(runId, "thr", "turn", "call1", "proj", lease,
                Instant.now().plusSeconds(600), null, null, mapper);
    }

    private ObjectNode args(Integer start, Integer end, Boolean forceFull) {
        ObjectNode a = mapper.createObjectNode();
        a.put("path", "sample.txt");
        if (start != null) {
            a.put("startLine", start);
        }
        if (end != null) {
            a.put("endLine", end);
        }
        if (forceFull != null) {
            a.put("forceFull", forceFull);
        }
        return a;
    }

    @Test
    void firstReadReturnsBody() throws Exception {
        ToolOutcome o = tool.execute(args(1, 5, null), ctx("r1"));
        assertEquals("SUCCEEDED", o.status());
        assertTrue(o.text().contains("UNIQUE_BODY_LINE_3"));
        assertFalse(o.structuredContent().path("alreadyRead").asBoolean());
        assertEquals(1, o.structuredContent().path("readCount").asInt());
    }

    @Test
    void repeatedReadOfSameUnchangedRangeReturnsReferenceWithoutBody() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 5, null), c);
        ToolOutcome again = tool.execute(args(1, 5, null), c);
        assertEquals("SUCCEEDED", again.status());
        assertTrue(again.structuredContent().path("alreadyRead").asBoolean());
        // 引用视图不得重复正文，否则去重无意义
        assertFalse(again.text().contains("UNIQUE_BODY_LINE_3"));
        assertTrue(again.text().contains("forceFull"));
        assertTrue(again.structuredContent().has("sha256"));
        assertTrue(again.structuredContent().path("coveredRanges").isArray());
    }

    @Test
    void forceFullReturnsBodyAgain() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 5, null), c);
        ToolOutcome forced = tool.execute(args(1, 5, true), c);
        assertFalse(forced.structuredContent().path("alreadyRead").asBoolean());
        assertTrue(forced.text().contains("UNIQUE_BODY_LINE_3"));
    }

    @Test
    void readingNewLinesReturnsBody() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 5, null), c);
        ToolOutcome next = tool.execute(args(6, 10, null), c);
        assertFalse(next.structuredContent().path("alreadyRead").asBoolean());
        assertTrue(next.text().contains("UNIQUE_BODY_LINE_8"));
        assertFalse(next.text().contains("UNIQUE_BODY_LINE_3"));
    }

    @Test
    void partiallyOverlappingRangeReturnsBodyForUncoveredLines() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 5, null), c);
        ToolOutcome overlap = tool.execute(args(4, 10, null), c);
        assertFalse(overlap.structuredContent().path("alreadyRead").asBoolean());
        assertTrue(overlap.text().contains("UNIQUE_BODY_LINE_8"));
    }

    @Test
    void subRangeAlreadyCoveredIsDeduplicated() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 10, null), c);
        ToolOutcome sub = tool.execute(args(3, 7, null), c);
        assertTrue(sub.structuredContent().path("alreadyRead").asBoolean());
        assertFalse(sub.text().contains("UNIQUE_BODY_LINE_5"));
    }

    @Test
    void fileChangeInvalidatesDedup() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 10, null), c);
        // 修改文件：字节变化，旧指纹必须失效
        Files.writeString(file, Files.readString(file).replace("UNIQUE_BODY_LINE_3", "CHANGED_LINE_3"));
        ToolOutcome after = tool.execute(args(1, 10, null), c);
        assertFalse(after.structuredContent().path("alreadyRead").asBoolean());
        assertTrue(after.text().contains("CHANGED_LINE_3"));
        assertEquals(1, after.structuredContent().path("readCount").asInt());
    }

    @Test
    void readsAreIsolatedPerRun() throws Exception {
        tool.execute(args(1, 5, null), ctx("runA"));
        ToolOutcome otherRun = tool.execute(args(1, 5, null), ctx("runB"));
        assertFalse(otherRun.structuredContent().path("alreadyRead").asBoolean());
        assertTrue(otherRun.text().contains("UNIQUE_BODY_LINE_3"));
    }

    @Test
    void adjacentRangesMergeAndCoverWholeSpan() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 5, null), c);
        tool.execute(args(6, 10, null), c);
        // 1-5 与 6-10 相邻合并为 1-10，整段重读应命中去重
        ToolOutcome whole = tool.execute(args(1, 10, null), c);
        assertTrue(whole.structuredContent().path("alreadyRead").asBoolean());
        assertFalse(whole.text().contains("UNIQUE_BODY_LINE_1"));
    }

    @Test
    void releasedRunForgetsReads() throws Exception {
        ToolExecutionContext c = ctx("r1");
        tool.execute(args(1, 5, null), c);
        registry.release("r1");
        ToolOutcome afterRelease = tool.execute(args(1, 5, null), c);
        assertFalse(afterRelease.structuredContent().path("alreadyRead").asBoolean());
        assertTrue(afterRelease.text().contains("UNIQUE_BODY_LINE_3"));
    }
}
