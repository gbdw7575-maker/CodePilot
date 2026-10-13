package com.dsh.memory;

import com.dsh.agent.TokenEstimator;
import com.dsh.application.DshProperties;
import com.dsh.rag.RagDocument;
import com.dsh.rag.RagRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一装配记忆段落（S3 新增）：按 {@link MemoryPolicy} 配额与优先级，将 L4(语义) 与
 * L5(情景) 段落拼接为一段文本，供 {@code LoopSteps.assemble} 追加到 system（L6 基线
 * 之后、D-19 预算软提醒之前）。
 * <p>
 * 注入顺序：L6 基线（RulePrompt，已由 LoopSteps 生成）→ L4 语义（AutoMemory 索引）
 * → L5 情景（EpisodicMemory 召回）→ L_RAG 外部知识库证据 + 证据判断规则 → D-19 软提醒。
 * <p>
 * L_RAG 由程序默认检索（用最近一条 user 消息），cosine 阈值过滤低相关片段，避免
 * 不相关 query 污染；命中时附带证据判断规则，让模型在一次调用内自检证据相关性，
 * 不额外走路由模型。rag_search 工具保留作补查（Run 内有次数上限）。
 * <p>
 * 配额：L4 优先于 L5，L_RAG 用剩余配额；budgetTokens 为三者共享上限，依次消费；
 * 超出按 token 预算截断（二分查找保证不溢出）。L6 基线不参与配额、不截断。
 * <p>
 * Fail-open：L4 读取异常（MEMORY_DISABLED 等）、L5/RAG 召回为空或异常时，仅跳过
 * 对应段落，不阻断主 Loop；记忆段落整体可为空字符串。
 * <p>
 * 隔离：L4/L5/RAG 均按 projectId 隔离，不跨项目召回。
 */
@Component
public class MemoryAssembler {

    private static final Logger log = LoggerFactory.getLogger(MemoryAssembler.class);

    /** 证据判断规则：L_RAG 命中时附在片段之后，引导模型一次调用内自检，不增加路由轮次。 */
    private static final String EVIDENCE_RULES =
            "以上为外部知识库候选片段，不保证与问题相关。回答前自检：\n"
            + "- 片段中的实体/作品/版本是否与用户所指一致？\n"
            + "- 片段是否支持用户询问的事实？\n"
            + "证据充分：基于片段回答并注明来源。\n"
            + "证据不足：不得编造知识库中的事实，可说明无相关资料。\n"
            + "同名且无法确定指代：向用户澄清。\n"
            + "通用问题且资料不相关：忽略这些资料，用通用知识回答。";

    private final MemoryProvider memoryProvider;
    private final EpisodicMemoryService episodicService;
    private final MemoryPolicy policy;
    private final DshProperties properties;
    private final RagRetrievalService ragService;

    public MemoryAssembler(MemoryProvider memoryProvider,
                           EpisodicMemoryService episodicService,
                           MemoryPolicy policy, DshProperties properties,
                           RagRetrievalService ragService) {
        this.memoryProvider = memoryProvider;
        this.episodicService = episodicService;
        this.policy = policy;
        this.properties = properties;
        this.ragService = ragService;
    }

    /**
     * 装配 L4+L5 记忆段落。返回追加到 system 基线之后的文本（无内容时返回空字符串）。
     * budgetTokens 为 L4+L5 共享的 token 预算上限（由 {@link MemoryPolicy#memoryBudget} 计算）。
     */
    public String assembleMemory(String projectId, String currentIntent, int budgetTokens) {
        if (budgetTokens <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int remaining = budgetTokens;
        boolean any = false;

        // L4 语义记忆：AutoMemory 索引（优先消费配额）
        String l4 = recallL4(projectId);
        if (!l4.isBlank()) {
            String l4Trimmed = truncateToTokenBudget(l4, remaining);
            if (!l4Trimmed.isBlank()) {
                sb.append("\n\n## 相关经验记忆\n").append(l4Trimmed).append('\n');
                any = true;
                remaining -= TokenEstimator.estimateText(l4Trimmed);
                if (remaining < 0) {
                    remaining = 0;
                }
            }
        }

        // L5 情景记忆：EpisodicMemory 召回（用 L4 之后的剩余配额）
        if (remaining > 0) {
            String l5 = recallL5(projectId, currentIntent);
            if (!l5.isBlank()) {
                String l5Trimmed = truncateToTokenBudget(l5, remaining);
                if (!l5Trimmed.isBlank()) {
                    sb.append("\n\n## 相关历史情景\n").append(l5Trimmed).append('\n');
                    any = true;
                    remaining -= TokenEstimator.estimateText(l5Trimmed);
                    if (remaining < 0) {
                        remaining = 0;
                    }
                }
            }
        }

        // L_RAG 外部知识库：程序默认检索（最近一条 user 消息），cosine 阈值已在 recall 内过滤。
        // 命中时注入片段 + 证据判断规则（规则 token 先预留，避免溢出预算）。
        int rulesTokens = TokenEstimator.estimateText(EVIDENCE_RULES);
        if (remaining > rulesTokens) {
            String rag = recallRag(projectId, currentIntent);
            if (!rag.isBlank()) {
                String ragTrimmed = truncateToTokenBudget(rag, remaining - rulesTokens);
                if (!ragTrimmed.isBlank()) {
                    sb.append("\n\n## 外部知识库证据\n").append(ragTrimmed).append('\n');
                    sb.append(EVIDENCE_RULES);
                    any = true;
                    remaining = 0;
                }
            }
        }

        return any ? sb.toString() : "";
    }

    /** L_RAG 外部知识库召回：cosine 阈值过滤后拼接片段，每段附来源。fail-open。 */
    private String recallRag(String projectId, String currentIntent) {
        if (projectId == null || projectId.isBlank()
                || currentIntent == null || currentIntent.isBlank()) {
            return "";
        }
        DshProperties.Rag rag = properties.rag();
        if (rag == null || !rag.enabled()) {
            return "";
        }
        try {
            int topK = rag.topK() <= 0 ? 3 : rag.topK();
            List<RagDocument> hits = ragService.recall(projectId, currentIntent, topK);
            if (hits.isEmpty()) {
                return "";
            }
            int per = rag.chunkSize() <= 0 ? 1000 : rag.chunkSize();
            StringBuilder sb = new StringBuilder();
            int index = 0;
            for (RagDocument d : hits) {
                if (index > 0) {
                    sb.append('\n');
                }
                String t = d.text() == null ? "" : d.text();
                String trimmed = t.length() > per ? t.substring(0, per) : t;
                sb.append(++index).append(". ").append(trimmed);
                if (d.heading() != null && !d.heading().isBlank()) {
                    sb.append("\n   来源: ").append(d.heading());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("RAG recall skipped for project {}: {}", projectId, e.getMessage());
            return "";
        }
    }

    /** L4 读取：Auto Memory 全局/项目关闭时返回空（fail-open，不抛 MEMORY_DISABLED）。 */
    private String recallL4(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return "";
        }
        if (properties.memory() == null
                || !properties.memory().enabled()
                || !properties.memory().readEnabled()) {
            return "";
        }
        try {
            MemoryIndex index = memoryProvider.retrieve(projectId);
            if (index == null || !index.enabled() || index.text() == null
                    || index.text().isBlank()) {
                return "";
            }
            return index.text();
        } catch (Exception e) {
            // MEMORY_DISABLED 或读取异常：fail-open，不阻断主 Loop
            log.debug("L4 memory recall skipped for project {}: {}", projectId, e.getMessage());
            return "";
        }
    }

    /** L5 读取：情景记忆召回，按来源 run 标注便于模型引用。 */
    private String recallL5(String projectId, String currentIntent) {
        if (projectId == null || projectId.isBlank()
                || currentIntent == null || currentIntent.isBlank()) {
            return "";
        }
        List<EpisodicMemoryEntry> hits = episodicService.recall(projectId, currentIntent);
        if (hits.isEmpty()) {
            return "";
        }
        int per = properties.episodic() == null
                ? 200 : properties.episodic().summaryMaxChars();
        StringBuilder sb = new StringBuilder();
        for (EpisodicMemoryEntry e : hits) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- [").append(shortState(e.getFinalState())).append("] ");
            String sum = e.getSummaryText() == null ? "" : e.getSummaryText();
            sb.append(sum.length() > per ? sum.substring(0, per) : sum);
            sb.append(" (来源 run: ").append(e.getSourceRunId()).append(')');
        }
        return sb.toString();
    }

    /**
     * 按 token 预算截断文本：二分查找最大前缀长度，保证截断后 token 数 ≤ budget。
     * CJK 与拉丁字符的 token 密度不同（CJK 约 1 token/char，拉丁约 1/4 token/char），
     * 逐前缀估算后二分保证不溢出预算，比固定字符数截断更稳健。
     */
    static String truncateToTokenBudget(String text, int tokenBudget) {
        if (text == null || text.isEmpty() || tokenBudget <= 0) {
            return "";
        }
        int tokens = TokenEstimator.estimateText(text);
        if (tokens <= tokenBudget) {
            return text;
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (TokenEstimator.estimateText(text.substring(0, mid)) <= tokenBudget) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo > 0 ? text.substring(0, lo) : "";
    }

    private String shortState(String state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return state.length() <= 12 ? state : state.substring(0, 12);
    }
}
