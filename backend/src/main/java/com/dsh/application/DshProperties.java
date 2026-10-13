package com.dsh.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** dsh.* 配置：安全边界、根 Run 初始预算视图、Loop 调度方式、工作区与沙箱。 */
@ConfigurationProperties(prefix = "dsh")
public record DshProperties(Security security, Budget budget, Loop loop, Workspace workspace,
                           Sandbox sandbox, Policy policy, Provider provider,
                           ToolSearch toolSearch, Memory memory, Orchestration orchestration,
                           Rules rules, Web web, Mcp mcp, Episodic episodic, Rag rag) {

    public DshProperties {
        if (security == null) {
            security = new Security(java.util.List.of());
        }
        if (budget == null) {
            budget = new Budget(24, 80, 200000, 20, 128000);
        } else if (budget.contextWindowTokens() <= 0) {
            // 兼容旧配置或部分绑定：context-window-tokens 未配置时回退默认 128000
            budget = new Budget(budget.maxModelRequests(), budget.maxToolCalls(),
                    budget.maxCumulativeTokens(), budget.deadlineMinutes(), 128000);
        }
        if (loop == null) {
            loop = new Loop(false);
        }
        if (workspace == null) {
            workspace = new Workspace(null);
        }
        if (sandbox == null) {
            sandbox = new Sandbox("local", "dsh-sandbox:latest", "512m", "2.0", 60, 600, 262144);
        }
        if (policy == null) {
            policy = new Policy(10, java.util.List.of("WORKSPACE_WRITE", "EXECUTE"));
        }
        if (provider == null) {
            provider = new Provider(10, 120, 3, 500);
        }
        if (toolSearch == null) {
            toolSearch = new ToolSearch(false, 60, 5, 5, false, 0.5);
        }
        if (memory == null) {
            memory = new Memory(true, true, true, "AGENTS.md", 200, 25000, ".myagent/memory", 0.15);
        } else if (memory.memoryBudgetRatio() <= 0) {
            // S3 Policy 治理层：memoryBudgetRatio 缺失/非正时回退默认 0.15
            memory = new Memory(memory.enabled(), memory.readEnabled(), memory.writeEnabled(),
                    memory.rulesFile(), memory.indexMaxLines(), memory.indexMaxBytes(),
                    memory.memoryDir(), 0.15);
        }
        if (orchestration == null) {
            // EXTENSION：默认关闭；先有单 Agent 基线，相同配置对照（开发文档 §17）有收益再启用
            orchestration = new Orchestration(false, 2, 8, 30, 60000, 600, false, 4,
                    Orchestration.Plan.defaults(), Orchestration.Quota.defaults(), 3, 500, 1.5);
        } else {
            // MA-02：maxFanout 与计划上限逐项兜底，缺失/非正回退冻结默认，不抬升合法配置
            int maxFanout = orchestration.maxFanout() <= 0 ? 4 : orchestration.maxFanout();
            var plan = (orchestration.plan() == null
                    ? Orchestration.Plan.defaults() : orchestration.plan()).normalized();
            var quota = (orchestration.quota() == null
                    ? Orchestration.Quota.defaults() : orchestration.quota()).normalized();
            // MA-08：retry 配置逐字段兜底，缺失/非正回退默认，不抬升合法配置
            int maxRetryAttempts = orchestration.maxRetryAttempts() <= 0
                    ? 3 : orchestration.maxRetryAttempts();
            int retryBackoffBaseMs = orchestration.retryBackoffBaseMs() <= 0
                    ? 500 : orchestration.retryBackoffBaseMs();
            // MA-11c-1：BUDGET_LIMIT 重试收口系数。null/负数回退默认 1.5；
            // 0 = 显式关闭收口（恢复 MA-08 既有行为）。（用包装类型区分「未配置」与「配 0」，
            // 否则绑定缺省的 0.0 会被误判为关闭。）
            double retryBudgetSafetyFactor = orchestration.retryBudgetSafetyFactor() == null
                    || orchestration.retryBudgetSafetyFactor() < 0
                    ? 1.5 : orchestration.retryBudgetSafetyFactor();
            orchestration = new Orchestration(orchestration.enabled(), orchestration.maxDepth(),
                    orchestration.subMaxModelRequests(), orchestration.subMaxToolCalls(),
                    orchestration.subMaxTokens(), orchestration.subTimeoutSeconds(),
                    orchestration.workerCanSpawn(), maxFanout, plan, quota,
                    maxRetryAttempts, retryBackoffBaseMs, retryBudgetSafetyFactor);
        }
        if (rules == null) {
            rules = new Rules("AGENTS.md", 262144, 4, 20);
        }
        if (web == null) {
            web = new Web(false, false, 5, 20, 524288, 20000, "");
        }
        if (mcp == null) {
            mcp = new Mcp(false, "", null, false, 5, 20, 20000);
        } else if (mcp.endpoint() == null || mcp.connectTimeoutSeconds() <= 0
                || mcp.readTimeoutSeconds() <= 0 || mcp.maxChars() <= 0) {
            // 逐字段兜底：仅对缺失/非正值回退默认，不得抬升合法配置（如测试用的较小 maxChars）
            mcp = new Mcp(mcp.enabled(),
                    mcp.endpoint() == null ? "" : mcp.endpoint(),
                    mcp.credentialRef(),
                    mcp.allowLoopback(),
                    mcp.connectTimeoutSeconds() <= 0 ? 5 : mcp.connectTimeoutSeconds(),
                    mcp.readTimeoutSeconds() <= 0 ? 20 : mcp.readTimeoutSeconds(),
                    mcp.maxChars() <= 0 ? 20000 : mcp.maxChars());
        }
        // S2 L5 情景记忆：默认开启（纯通用能力，无外部依赖）
        if (episodic == null) {
            episodic = new Episodic(true, 3, 2, 10, 0.05, 0.5, 200);
        } else {
            // 逐字段兜底：recallTopN/minKeywords 至少为 1，避免零值导致召回永远为空
            int recallTopN = episodic.recallTopN() <= 0 ? 3 : episodic.recallTopN();
            int minKeywords = episodic.minKeywords() <= 0 ? 2 : episodic.minKeywords();
            int recentDays = episodic.recentDays() <= 0 ? 10 : episodic.recentDays();
            double decayPerDay = episodic.decayPerDay() <= 0 ? 0.05 : episodic.decayPerDay();
            double decayFloor = episodic.decayFloor() < 0 ? 0.5 : episodic.decayFloor();
            int summaryMaxChars = episodic.summaryMaxChars() <= 0 ? 200 : episodic.summaryMaxChars();
            episodic = new Episodic(episodic.enabled(), recallTopN, minKeywords, recentDays,
                    decayPerDay, decayFloor, summaryMaxChars);
        }
        // RAG 检索（R4 新增）：默认关闭（EXTENSION），与 ToolSearch/MCP 一致
        if (rag == null) {
            rag = new Rag(false, "doc/外部知识库", ".myagent/rag", 1000, 3, 60, true, "char-ngram", 0.55, 3, 3);
        } else {
            int chunkSize = rag.chunkSize() <= 0 ? 1000 : rag.chunkSize();
            int topK = rag.topK() <= 0 ? 3 : rag.topK();
            int rrfK = rag.rrfK() <= 0 ? 60 : rag.rrfK();
            String sources = rag.sources() == null || rag.sources().isBlank()
                    ? "doc/外部知识库" : rag.sources();
            String storageDir = rag.storageDir() == null || rag.storageDir().isBlank()
                    ? ".myagent/rag" : rag.storageDir();
            String embeddingProvider = rag.embeddingProvider() == null
                    || rag.embeddingProvider().isBlank() ? "char-ngram" : rag.embeddingProvider();
            rag = new Rag(rag.enabled(), sources, storageDir, chunkSize, topK, rrfK,
                    rag.bm25Enabled(), embeddingProvider, rag.cosineFloor(),
                    rag.maxRagSearchesPerRun(), rag.bm25MinHits());
        }
    }

    public record Security(java.util.List<String> allowedRoots) {
    }

    /**
     * D-15 外部资料（EXTENSION）：只读 web_search/web_fetch 的网络授权与界限。
     * enabled=false 时工具不可发现也不可执行（未经授权不联网）；allowLoopback 仅为
     * 测试/本机联调打开环回访问，生产默认拒绝私有/环回地址（SSRF 防护）。
     */
    public record Web(boolean enabled, boolean allowLoopback, int connectTimeoutSeconds,
                      int readTimeoutSeconds, int maxBytes, int maxChars, String searchEndpoint) {

        public Web {
            if (connectTimeoutSeconds <= 0) {
                connectTimeoutSeconds = 5;
            }
            if (readTimeoutSeconds <= 0) {
                readTimeoutSeconds = 20;
            }
            if (maxBytes <= 0) {
                maxBytes = 524288;
            }
            if (maxChars <= 0) {
                maxChars = 20000;
            }
            if (searchEndpoint == null) {
                searchEndpoint = "";
            }
        }
    }

    /**
     * D-17b MCP 只读扩展（EXTENSION）：经 Streamable HTTP 接入一个只读 MCP server。
     * enabled=false 时工具不可发现也不可执行（fail-closed）；仅注册
     * annotations.readOnlyHint=true 的远程工具；allowLoopback 仅供本机联调
     * （生产拒绝环回/内网地址，SSRF 防护）。credentialRef 经 CredentialStore
     * 解析为 Authorization 头，密钥不进入日志、事件或工具输出。
     */
    public record Mcp(boolean enabled, String endpoint, String credentialRef,
                      boolean allowLoopback, int connectTimeoutSeconds,
                      int readTimeoutSeconds, int maxChars) {
    }

    /**
     * 根 Run 累计预算 + 单次上下文窗口。
     * contextWindowTokens 为单次模型请求的输入窗口上限（D-07），与累计 Run 预算分开；
     * 默认 128000 对应 DeepSeek V3 上下文长度。
     */
    public record Budget(int maxModelRequests, int maxToolCalls, int maxCumulativeTokens,
                         int deadlineMinutes, int contextWindowTokens) {
    }

    /**
     * synchronous=true：提交事务提交后在同一请求内跑完 Loop（离线确定性，测试使用）；
     * false：单线程后台执行，submit 只承诺 202。
     */
    public record Loop(boolean synchronous) {
    }

    /**
     * 隔离任务副本根目录；null 表示使用系统临时目录下 dsh-workspaces。
     * 每次 Run 在其下建立独立副本，不直接操作用户项目目录。
     */
    public record Workspace(String root) {
    }

    /**
     * 命令沙箱：mode=docker 时命令只在容器执行（无网络、非 root、资源上限、无 docker socket）；
     * mode=local 仅供开发与测试的真实进程执行，不等于生产隔离。
     * outputLimitBytes 为 stdout/stderr 各自的有界日志上限；超出时 truncated=true，
     * 并保留首尾、丢弃中间（A3）。注意：被丢弃的中间字节不会进入 ArtifactStore，
     * 因此「完整产物另行落盘」只对未触达该上限的输出成立。
     */
    public record Sandbox(String mode, String image, String memory, String cpus,
                          int defaultTimeoutSeconds, int maxTimeoutSeconds, int outputLimitBytes) {
    }

    /**
     * 审批策略：READ_ONLY 工具直接放行；
     * requireForSideEffects 中列出的副作用（WORKSPACE_WRITE/EXECUTE）需人工审批。
     */
    public record Policy(int approvalTimeoutMinutes, java.util.List<String> requireForSideEffects) {
    }

    /**
     * 模型供应商网关配置：连接/读超时、有界重试退避。
     * maxRetries 为首次失败后的额外重试次数；retryBaseDelayMs 为指数退避基数。
     */
    public record Provider(int connectTimeoutSeconds, int readTimeoutSeconds,
                           int maxRetries, long retryBaseDelayMs) {
    }

    /**
     * Tool Search（DSH-009，EXTENSION）：tool_search 元工具开关默认关闭，
     * 是否启用应看工具规模与相同配置对照的实测收益（开发文档 §6/§17）。
     * rrfK 为 RRF 融合冻结常数；maxResults 为单次返回上限（契约 top-5）；
     * maxSearchesPerRun 限制无进展重复查询；vectorEnabled 关闭向量腿（仅 BM25 单路）；
     * minBm25Score 为 BM25 最低相关分数阈值（D-11：低相关返回空，过滤弱命中噪声）。
     */
    public record ToolSearch(boolean enabled, int rrfK, int maxResults,
                             int maxSearchesPerRun, boolean vectorEnabled, double minBm25Score) {
    }

    /**
     * Auto Memory（DSH-009，EXTENSION）：经验记忆读写开关与项目隔离存储。
     * 规则文件默认 AGENTS.md（可配置为 CLAUDE.md），Auto Memory 永不写规则文件；
     * 索引读取上限 200 行 / 25000 UTF-8 字节先到者（开发文档 §10.3）。
     * <p>
     * memoryBudgetRatio（S3 新增）：assemble 阶段 L4(语义)+L5(情景) 记忆段落可占用的
     * token 配额比例（相对于扣除 system 基线/工具/预留输出/历史后的窗口余额），默认 0.15。
     */
    public record Memory(boolean enabled, boolean readEnabled, boolean writeEnabled,
                         String rulesFile, int indexMaxLines, int indexMaxBytes, String memoryDir,
                         double memoryBudgetRatio) {
        public Memory {
            if (memoryBudgetRatio <= 0) {
                memoryBudgetRatio = 0.15;
            }
        }
    }

    /**
     * 子 Agent / 可选编排（DSH-010，EXTENSION）：总预算以根 Run 为总账、子 Run 分账。
     * maxDepth 为派生链深度上限（MAIN=0）；sub* 为单个子 Run 的预算切片上限与墙钟超时；
     * workerCanSpawn=false 时子 Run 不能再调用 run_subagent/start_orchestration（初版固定 false）。
     *
     * <p>MA-08 限定重试（§10.2）：maxRetryAttempts 为单 attempt 最大重试次数（默认 3，
     * 含首次执行，超限落 FAILED/RETRY_LIMIT_EXCEEDED）；retryBackoffBaseMs 为退避基数
     * （默认 500ms，退避=base*2^(retryCount)，有上限计入预算，补偿扫描跳过未到时间的 attempt）。</p>
     *
     * <p>MA-11c-1 重试收口：retryBudgetSafetyFactor（默认 1.5）用于 `BUDGET_LIMIT` 重试前的
     * 额度校验——父 Run 剩余可分配额度 &lt; 上次子 Run 切片 × 该系数时，重开子 Run 必然再次
     * 撞上限（实测：默认切片下 16/16 子 Run 全部 BUDGET_LIMIT、rework 24），直接落 FAILED
     * （reasonCode=BUDGET_LIMIT_NO_RETRY）不再排队。配 0 显式关闭收口，恢复 MA-08 行为。</p>
     *
     * <p>注：MA-11 §17 的 {@code dsh.orchestration.acceptance-coverage-mode} 不在此 record 内，
     * 按本项目约定（MA-11 §14 先例）以 {@code @Value} 直接注入 {@code TaskVerificationService}，
     * 避免 record 签名变更波及其它模块与测试。</p>
     */
    public record Orchestration(boolean enabled, int maxDepth, int subMaxModelRequests,
                                int subMaxToolCalls, int subMaxTokens, int subTimeoutSeconds,
                                boolean workerCanSpawn, int maxFanout, Plan plan, Quota quota,
                                int maxRetryAttempts, int retryBackoffBaseMs,
                                Double retryBudgetSafetyFactor) {

        /**
         * MA-02 计划图上限（Agent 编排升级规范 §4.3/§12 冻结）。计划能力本身不门控：
         * update_plan 只写惰性草案，不启动执行；这些上限只约束草案体积。
         */
        public record Plan(int maxTasksPerPlan, int maxDepsPerTask, int maxInputRefsPerTask,
                           int dispatchMaxBatch, int awaitDefaultTimeoutMs) {

            public static Plan defaults() {
                return new Plan(12, 6, 8, 12, 120000);
            }

            Plan normalized() {
                return new Plan(
                        maxTasksPerPlan <= 0 ? 12 : maxTasksPerPlan,
                        maxDepsPerTask <= 0 ? 6 : maxDepsPerTask,
                        maxInputRefsPerTask <= 0 ? 8 : maxInputRefsPerTask,
                        dispatchMaxBatch <= 0 ? 12 : dispatchMaxBatch,
                        awaitDefaultTimeoutMs <= 0 ? 120000 : awaitDefaultTimeoutMs);
            }
        }

        /**
         * MA-04 调度准备配额（规范 §6.2 有界执行、§10.1 RootBudget 口径）。
         * globalMaxWorkers=应用全局 worker 并发；rootMaxWorkers=单根 Run 内并发；
         * providerMaxWorkers=同一模型提供方并发。三级信号量同时满足才放一个 worker。
         * mainReserve* 为预算预约时必须留给主 Agent（汇总/最终验证）的三维保留额，
         * 使可分配额度=根上限−已结算−进行中预约−主任务保留；保留额允许显式配 0。
         */
        public record Quota(int globalMaxWorkers, int rootMaxWorkers, int providerMaxWorkers,
                            int mainReserveModelRequests, int mainReserveToolCalls,
                            int mainReserveTokens) {

            public static Quota defaults() {
                return new Quota(8, 4, 4, 4, 8, 40_000);
            }

            public Quota normalized() {
                return new Quota(
                        globalMaxWorkers <= 0 ? 8 : globalMaxWorkers,
                        rootMaxWorkers <= 0 ? 4 : rootMaxWorkers,
                        providerMaxWorkers <= 0 ? 4 : providerMaxWorkers,
                        Math.max(0, mainReserveModelRequests),
                        Math.max(0, mainReserveToolCalls),
                        Math.max(0, mainReserveTokens));
            }
        }
    }

    /**
     * 项目规则注入（D-04）：file 为规则文件名（默认 AGENTS.md，可配置 CLAUDE.md 兼容来源）；
     * maxBytes 为单文件读取上限（超出截断并标记）；maxDepth 为目录规则扫描深度；
     * maxFiles 为单次注入的规则文件数上限。规则是可信项目约定，只指导模型，
     * 不提升工作区权限、不绕过审批与执行策略。
     */
    public record Rules(String file, int maxBytes, int maxDepth, int maxFiles) {
    }

    /**
     * L5 情景记忆（S2 新增）：从历史 Run 摘要召回与当前用户意图相关的情景，
     * 注入 assemble 作为 system 段落。跨会话、按 projectId 隔离。
     * <p>
     * enabled 默认 true（纯通用能力，无外部依赖，可关）；recallTopN 为单次召回上限（默认 3）；
     * minKeywords 为召回需命中的最少关键词数（默认 2，过滤噪声）；recentDays 为召回窗口天数（默认 10）；
     * decayPerDay/decayFloor 控制时间衰减权重（近期事件权重高）；summaryMaxChars 为单条情景摘要字符上限。
     */
    public record Episodic(boolean enabled, int recallTopN, int minKeywords, int recentDays,
                           double decayPerDay, double decayFloor, int summaryMaxChars) {
    }

    /**
     * RAG 检索（R4 新增，第 16 章）：从外部知识库召回与查询相关的文档片段，经 rag_search 工具
     * 按需调用（不再自动注入 assemble）。默认关闭（EXTENSION，与 ToolSearch/MCP 一致）；
     * sources 为文档目录（相对项目根）；storageDir 为向量存储目录；chunkSize 为切分上限；
     * topK 为召回数；rrfK 为 RRF 融合参数；bm25Enabled 控制关键词腿；
     * embeddingProvider 为嵌入实现（char-ngram 零依赖，可扩展 api）；
     * cosineFloor 为向量腿 cosine 相似度下限，低于阈值的片段不计分（避免不相关 query 污染）。
     */
    public record Rag(boolean enabled, String sources, String storageDir, int chunkSize,
                      int topK, int rrfK, boolean bm25Enabled, String embeddingProvider,
                      double cosineFloor, int maxRagSearchesPerRun, int bm25MinHits) {
        public Rag {
            if (cosineFloor <= 0) {
                cosineFloor = 0.55;
            }
            if (maxRagSearchesPerRun <= 0) {
                maxRagSearchesPerRun = 3;
            }
            if (bm25MinHits <= 0) {
                bm25MinHits = 3;
            }
        }
    }
}
