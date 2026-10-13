# CodePilot Harness

面向本地代码仓库的 AI Coding Agent，基于 Java 21 自研 ReAct 运行时，支持上下文管理、RAG、工具调用、持久化多 Agent 调度与执行链路追踪。

## 已记录的测试与评测结果

| 验证 | 已记录结果 | 范围 |
|---|---|---|
| SWE-bench 同配置实例组 | **40 实例：27 resolved / 8 unresolved / 5 empty_patch / 0 error** | batch01–04；每实例一次 Agent 尝试，按修正后的评分记录统计 |
| 强验收相关定向测试 | **128 项 / 7 套件，0 失败、0 错误、0 跳过** | 2026-10-04 的对应构建；包含真实 PostgreSQL、文件证据与状态竞争测试 |
| Docker 恢复围栏 | **6/6 通过** | 迟到 create/start、重启复用、归属冲突及围栏丢失拒绝 |
| 真实 DeepSeek 单／多 Agent 对照 | **两侧均正常完成，独立检查各 7/7** | 两个固定独立 Java 子任务；MULTI 两项任务验收成功并实际合并 |
| 上下文窗口压力对照 | **40K 配置下完成 41 次请求并正常收尾** | 同一实例的对照：旧臂第 20 次请求后 CONTEXT_LIMIT；两侧补丁均通过独立评分 |

这些数字来自已有记录，本次公开源码未重新运行模型或全量回归。各组使用不同历史构建，不能合并成当前源码的统一通过率。

**SWE-bench 环境说明：**使用官方评分器流程，本地修改过临时文件清理代码；实例镜像来自 `ghcr.nju.edu.cn`，未与 Docker Hub 校验字节一致。5 个空补丁实例未评分；结果只报告这组实例的绝对量，不代表公开榜单成绩。两个实例因补丁提取问题重评分，未重新调用模型。

[详细结果与范围](docs/评测结果.md) · [40 实例清单](docs/results/swebench-batch40.json) · [验收与复现](docs/测试与复现.md)

## 核心能力

- **Agent 编排**：ReAct 循环与 PlanTool，流式工具调用、预算预约、审批暂停、取消和恢复。
- **上下文工程**：目录规则、工具调用与结果配对、历史压缩、长输出摘要与完整 Artifact 留存。
- **RAG 与工具发现**：字符 n-gram 向量／关键词双路召回、RRF 融合、相关性过滤；BM25 工具检索与 Run 级按需挂载。
- **多 Agent**：持久化任务依赖图、就绪队列、CAS 领取、幂等分派、独立工作区、并发配额及预算预约。
- **可信验收**：实际交付、报告与验收凭据绑定；下游、合并及根任务结束前重验当前性，未知执行状态保守恢复。
- **可观测性**：主子 Run 的 Trace 关联、结构化错误码、事件持久化与补拉、请求级上下文和 Token 用量观测。

## 技术栈

Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL · DeepSeek API · Java HttpClient / SSE · JSON Schema · Docker · React 19 / TypeScript · WebSocket · Git · ripgrep

## 使用入口

需要 Java 21、Docker、PostgreSQL，以及前端开发环境。后端作为可信控制进程调用 Docker CLI，模型产生的命令在 Docker 沙箱执行。

- [运行与配置](docs/运行与配置.md)：数据库、服务、模型凭据和功能开关。
- [测试与复现](docs/测试与复现.md)：构建、契约检查、测试环境和评测范围。
- [接口与事件](docs/接口与事件.md)：OpenAPI、事件与工具 Schema。

多 Agent、强模式选择和强运行默认关闭，需受控配置及用户确认后启用。小样本 MULTI 耗时约25分34秒，SINGLE 约27秒，尚未证明多 Agent 的性能或成本收益。

## 仓库内容

```text
backend/       Java 后端、单元及集成测试
frontend/      React 工作台、API 类型和前端检查脚本
sandbox/       命令沙箱 Dockerfile 与固定 ripgrep 构建输入
doc/           契约生成源及机器可读契约
docs/          精选运行、接口、测试和评测文档
evals/         自有评分器与可信验收参考输入
```

公开版本只保留项目运行、开发及验收所需内容。原始数据库、日志、API Key、工作区副本、第三方仓库、离线缓存、学习／面试资料和逐阶段开发日志不纳入仓库。
