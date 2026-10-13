package com.dsh.agent.tool;

import com.dsh.agent.tool.discovery.DiscoveryResult;
import com.dsh.agent.tool.discovery.EmbeddingClient;
import com.dsh.agent.tool.discovery.ToolDiscoveryService;
import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-009 Tool Discovery 契约（内部接口契约 §1 / 开发文档 §7）：
 * 授权过滤 → BM25+向量各自召回 → RRF 融合（rank 从 1，k 冻结）→ 去重 → top-5；
 * 向量失败 BM25 降级须标 degraded；空结果=成功空列表。
 */
class ToolDiscoveryServiceTest {

    private static ObjectProvider<EmbeddingClient> provider(EmbeddingClient client) {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingClient getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EmbeddingClient getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public EmbeddingClient getIfAvailable() {
                return client;
            }

            @Override
            public EmbeddingClient getIfUnique() {
                return client;
            }
        };
    }

    /** 确定性向量腿：按传入 docs 顺序原序返回（rank=列表序）。 */
    private static final class FixedEmbeddingClient implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            return new float[128];
        }

        @Override
        public int dimension() {
            return 128;
        }

        @Override
        public List<Scored> rank(String query, List<Doc> docs) {
            List<Scored> result = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                result.add(new Scored(docs.get(i).docId(), 1.0 - i * 0.01));
            }
            return result;
        }
    }

    private static final class FailingEmbeddingClient implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            throw new IllegalStateException("embedding service down");
        }

        @Override
        public int dimension() {
            return 128;
        }

        @Override
        public List<Scored> rank(String query, List<Doc> docs) {
            throw new IllegalStateException("embedding service down");
        }
    }

    private static Tool extensionTool(String name, String description, boolean authorized) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolDefinition customDefinition() {
                ObjectNode schema = JsonNodeFactory.instance.objectNode();
                schema.put("type", "object");
                return new ToolDefinition(name, description, "1.0.0", "EXTENSION", "NONE", schema);
            }

            @Override
            public ExtensionMeta extensionMeta() {
                return new ExtensionMeta(description, authorized);
            }

            @Override
            public com.dsh.agent.tool.ToolOutcome execute(com.fasterxml.jackson.databind.JsonNode arguments,
                                                          com.dsh.agent.tool.ToolExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** 带参数名/参数说明的扩展工具，用于验证 BM25 覆盖参数语义。 */
    private static Tool extensionToolWithParams(String name, String description, boolean authorized,
                                                String... paramPairs) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolDefinition customDefinition() {
                ObjectNode schema = JsonNodeFactory.instance.objectNode();
                schema.put("type", "object");
                ObjectNode properties = JsonNodeFactory.instance.objectNode();
                for (int i = 0; i + 1 < paramPairs.length; i += 2) {
                    ObjectNode field = JsonNodeFactory.instance.objectNode();
                    field.put("type", "string");
                    field.put("description", paramPairs[i + 1]);
                    properties.set(paramPairs[i], field);
                }
                schema.set("properties", properties);
                return new ToolDefinition(name, description, "1.0.0", "EXTENSION", "NONE", schema);
            }

            @Override
            public ExtensionMeta extensionMeta() {
                return new ExtensionMeta(description, authorized);
            }

            @Override
            public com.dsh.agent.tool.ToolOutcome execute(com.fasterxml.jackson.databind.JsonNode arguments,
                                                          com.dsh.agent.tool.ToolExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ToolRegistry registry(Tool... tools) throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(tools), new com.fasterxml.jackson.databind.ObjectMapper());
        registry.initialize();
        return registry;
    }

    private static ToolDiscoveryService service(ToolRegistry registry, EmbeddingClient client) {
        // 显式配置：k=60、top-5、向量开启、minBm25Score=0（保留既有断言行为）
        DshProperties properties = new DshProperties(null, null, null, null, null, null, null,
                new DshProperties.ToolSearch(true, 60, 5, 5, true, 0.0), null, null, null, null, null, null, null);
        return new ToolDiscoveryService(registry, properties, provider(client));
    }

    private static ToolDiscoveryService serviceWithMinScore(ToolRegistry registry,
                                                            EmbeddingClient client, double minScore) {
        DshProperties properties = new DshProperties(null, null, null, null, null, null, null,
                new DshProperties.ToolSearch(true, 60, 5, 5, true, minScore), null, null, null, null, null, null, null);
        return new ToolDiscoveryService(registry, properties, provider(client));
    }

    private static ToolDiscoveryService benchmarkService(ToolRegistry registry, boolean vectorEnabled) {
        DshProperties properties = new DshProperties(null, null, null, null, null, null, null,
                new DshProperties.ToolSearch(true, 60, 5, 5, vectorEnabled, 0.5),
                null, null, null, null, null, null, null);
        return new ToolDiscoveryService(registry, properties,
                provider(vectorEnabled ? new com.dsh.agent.tool.discovery.CharNgramEmbeddingClient() : null));
    }

    @Test
    void fixedQueryCatalogKeepsBm25AsDefaultAndMeasuresExperimentalFusion() throws Exception {
        ToolRegistry catalog = registry(
                extensionToolWithParams("memory_recall", "读取项目经验索引", true,
                        "entryId", "经验条目编号"),
                extensionToolWithParams("memory_save", "保存用户偏好和反馈", true,
                        "content", "需要记住的用户偏好"),
                extensionToolWithParams("run_subagent", "启动独立子代理", true,
                        "prompt", "子 agent 任务描述"),
                extensionToolWithParams("web_search", "搜索公开网页", true,
                        "query", "网络检索关键词"),
                extensionToolWithParams("web_fetch", "抓取网页内容", true,
                        "url", "网页地址 URL"),
                extensionToolWithParams("admin_shell", "搜索公开网页和读取项目经验索引", false,
                        "query", "管理员命令"));
        ToolDiscoveryService bm25 = benchmarkService(catalog, false);
        ToolDiscoveryService hybrid = benchmarkService(catalog, true);
        String[][] cases = {
                {"读取项目经验索引", "memory_recall"},
                {"保存用户偏好", "memory_save"},
                {"子 agent 任务描述", "run_subagent"},
                {"搜索公开网页", "web_search"},
                {"抓取网页内容", "web_fetch"}
        };
        int bm25Top1 = 0;
        int hybridTop1 = 0;
        int bm25Top5 = 0;
        int hybridTop5 = 0;
        long bm25Nanos = 0;
        long hybridNanos = 0;
        int bm25ResponseChars = 0;
        int hybridResponseChars = 0;
        int bm25ExtraMounts = 0;
        int hybridExtraMounts = 0;
        for (String[] sample : cases) {
            long start = System.nanoTime();
            DiscoveryResult primary = bm25.search(sample[0]);
            bm25Nanos += System.nanoTime() - start;
            start = System.nanoTime();
            DiscoveryResult experiment = hybrid.search(sample[0]);
            hybridNanos += System.nanoTime() - start;
            bm25Top1 += !primary.items().isEmpty() && sample[1].equals(primary.items().get(0).name()) ? 1 : 0;
            hybridTop1 += !experiment.items().isEmpty() && sample[1].equals(experiment.items().get(0).name()) ? 1 : 0;
            bm25Top5 += primary.items().stream().anyMatch(i -> sample[1].equals(i.name())) ? 1 : 0;
            hybridTop5 += experiment.items().stream().anyMatch(i -> sample[1].equals(i.name())) ? 1 : 0;
            bm25ResponseChars += primary.items().toString().length();
            hybridResponseChars += experiment.items().toString().length();
            bm25ExtraMounts += Math.max(0, primary.items().size() - 1);
            hybridExtraMounts += Math.max(0, experiment.items().size() - 1);
            assertFalse(primary.items().stream().anyMatch(i -> i.name().equals("admin_shell")));
            assertFalse(experiment.items().stream().anyMatch(i -> i.name().equals("admin_shell")));
            assertEquals("DISABLED", primary.vectorStatus());
        }
        assertEquals(5, bm25Top1, "固定查询集的 BM25 top-1 应全部命中目标工具");
        assertEquals(5, bm25Top5);
        assertTrue(hybridTop1 <= bm25Top1, "混合排序不得被宣称优于此基线");
        assertEquals(5, hybridTop5);
        for (ToolDiscoveryService candidate : List.of(bm25, hybrid)) {
            assertTrue(candidate.search(" ").items().isEmpty());
            assertTrue(candidate.search("完全不匹配的zxqv词").items().isEmpty());
            assertEquals(5, candidate.search("搜索公开网页").catalogSize(), "越权工具不得进入候选集");
        }
        System.out.printf("D11 fixed queries=5 bm25 top1=%d top5=%d extraMounts=%d ns=%d resultChars=%d; "
                        + "hybrid top1=%d top5=%d extraMounts=%d ns=%d resultChars=%d%n",
                bm25Top1, bm25Top5, bm25ExtraMounts, bm25Nanos, bm25ResponseChars,
                hybridTop1, hybridTop5, hybridExtraMounts, hybridNanos, hybridResponseChars);
    }

    @Test
    void unauthorizedToolsAreInvisibleToDiscovery() throws Exception {
        ToolRegistry registry = registry(
                extensionTool("memory_save", "保存经验 memory", true),
                extensionTool("admin_shell", "管理员命令入口", false));
        ToolDiscoveryService service = service(registry, new FixedEmbeddingClient());

        DiscoveryResult result = service.search("管理员命令");
        assertFalse(result.items().stream().anyMatch(i -> i.name().equals("admin_shell")),
                "未授权扩展工具对发现不可见");
        assertEquals(1, result.catalogSize(), "目录仅含授权候选");
    }

    @Test
    void rrfFusionRanksByRankReciprocalSum() throws Exception {
        ToolRegistry registry = registry(
                extensionTool("memory_save", "保存 memory 经验", true),
                extensionTool("memory_recall", "读取 memory 索引", true));
        ToolDiscoveryService service = service(registry, new FixedEmbeddingClient());

        DiscoveryResult result = service.search("memory");
        assertEquals(2, result.items().size());
        // BM25 与向量都把 save 排第 1（含查询词且列表序在前）、recall 第 2
        DiscoveryResult.Item first = result.items().get(0);
        assertEquals("memory_save", first.name());
        assertEquals(1.0 / (60 + 1) + 1.0 / (60 + 1), first.rrfScore(), 1e-9);
        assertEquals(1.0, first.bm25Rank());
        assertEquals(1.0, first.vectorRank());
        assertEquals("ACTIVE", result.vectorStatus());
        assertFalse(result.degraded());
    }

    @Test
    void topFiveLimitAndDeduplication() throws Exception {
        List<Tool> tools = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            tools.add(extensionTool("ext_tool_" + i, "工具 " + i + " 处理 memory 数据", true));
        }
        ToolDiscoveryService service = service(registry(tools.toArray(new Tool[0])),
                new FixedEmbeddingClient());

        DiscoveryResult result = service.search("memory");
        assertEquals(5, result.items().size(), "无论候选多少，单次最多返回 5 个");
        assertEquals(5, result.items().stream().map(DiscoveryResult.Item::name).distinct().count(),
                "融合结果必须去重");
    }

    @Test
    void vectorFailureFallsBackToBm25AndMarksDegraded() throws Exception {
        ToolRegistry registry = registry(
                extensionTool("memory_save", "保存 memory 经验", true),
                extensionTool("unrelated_tool", "完全无关的工具", true));
        ToolDiscoveryService service = service(registry, new FailingEmbeddingClient());

        DiscoveryResult result = service.search("memory");
        assertTrue(result.degraded(), "向量腿失败必须标记 degraded");
        assertEquals("DEGRADED", result.vectorStatus());
        // BM25 单路仍然可用：命中 memory_save，无关工具无 BM25 排名
        assertEquals(1, result.items().size());
        assertEquals("memory_save", result.items().get(0).name());
        assertEquals(1.0 / (60 + 1), result.items().get(0).rrfScore(), 1e-9);
        assertNotNull(result.items().get(0).bm25Rank());
        assertNull(result.items().get(0).vectorRank(), "降级路无向量排名");
    }

    @Test
    void blankQueryIsSuccessfulEmptyList() throws Exception {
        ToolRegistry registry = registry(extensionTool("memory_save", "保存经验", true));
        ToolDiscoveryService service = service(registry, new FixedEmbeddingClient());

        DiscoveryResult result = service.search("   ");
        assertTrue(result.items().isEmpty(), "空查询=成功空列表");
        assertFalse(result.degraded());
    }

    @Test
    void vectorDisabledRunsBm25Only() throws Exception {
        ToolRegistry registry = registry(extensionTool("memory_save", "保存 memory 经验", true));
        DshProperties properties = new DshProperties(null, null, null, null, null, null, null,
                new DshProperties.ToolSearch(true, 60, 5, 5, false, 0.0), null, null, null, null, null, null, null);
        ToolDiscoveryService service = new ToolDiscoveryService(registry, properties,
                provider(new FixedEmbeddingClient()));

        DiscoveryResult result = service.search("memory");
        assertEquals("DISABLED", result.vectorStatus());
        assertFalse(result.degraded());
        assertEquals(1, result.items().size());
    }

    @Test
    void catalogDigestIsStableAndChangesWithCatalog() throws Exception {
        ToolRegistry registry = registry(extensionTool("memory_save", "保存经验", true));
        ToolDiscoveryService service = service(registry, new FixedEmbeddingClient());
        String digest = service.catalogDigest();
        assertEquals(digest, service.catalogDigest());

        ToolRegistry changed = registry(extensionTool("memory_save", "保存经验（更新描述）", true));
        assertNotEquals(digest, service(changed, new FixedEmbeddingClient()).catalogDigest(),
                "目录内容变化必须反映在指纹中");
    }

    @Test
    void bm25CoversParameterNameAndDescription() throws Exception {
        // 参数名 prompt 与参数说明"子 agent 任务描述"不出现在名称/描述/何时使用中，
        // 仅当 BM25 索引覆盖参数时，查询"任务描述"才能命中 run_subagent。
        ToolRegistry registry = registry(
                extensionToolWithParams("run_subagent", "启动子代理", true,
                        "prompt", "子 agent 任务描述", "depth", "嵌套深度"),
                extensionToolWithParams("memory_recall", "读取经验索引", true,
                        "entryId", "经验条目编号"));
        ToolDiscoveryService service = service(registry, new FixedEmbeddingClient());

        DiscoveryResult result = service.search("任务描述");
        assertEquals(1, result.items().size(), "查询参数说明应命中对应工具");
        assertEquals("run_subagent", result.items().get(0).name(),
                "BM25 应覆盖参数说明字段");

        // 参数名本身也可召回
        DiscoveryResult byParamName = service.search("entryId");
        assertEquals(1, byParamName.items().size());
        assertEquals("memory_recall", byParamName.items().get(0).name(),
                "BM25 应覆盖参数名字段");
    }

    @Test
    void lowRelevanceReturnsEmptyWhenMinScoreSet() throws Exception {
        // minBm25Score 设置为较高阈值，弱命中（单高频词）被过滤，返回空列表。
        ToolRegistry registry = registry(
                extensionTool("memory_save", "保存 memory 经验", true),
                extensionTool("memory_recall", "读取 memory 索引", true));
        ToolDiscoveryService service = serviceWithMinScore(registry, new FixedEmbeddingClient(), 10.0);

        DiscoveryResult result = service.search("memory");
        assertTrue(result.items().isEmpty(),
                "BM25 分数低于阈值时应返回空列表（低相关不返回）");
        assertFalse(result.degraded());
    }

    @Test
    void noBm25HitReturnsEmptyEvenWhenVectorMatches() throws Exception {
        // 查询与所有工具的 BM25 文档均无重合；即使向量腿按序返回，最终结果应为空。
        ToolRegistry registry = registry(
                extensionTool("memory_save", "保存经验", true),
                extensionTool("memory_recall", "读取索引", true));
        ToolDiscoveryService service = service(registry, new FixedEmbeddingClient());

        DiscoveryResult result = service.search("完全无关的随机查询词xyz");
        assertTrue(result.items().isEmpty(),
                "BM25 主路无命中时，即使向量腿召回也不返回结果");
    }
}
