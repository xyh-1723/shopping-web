# Agent Harness 扩展设计

> 本文回答三个问题：**(1)** 业界说的「harness」到底指什么；**(2)** 本项目的 assistant 模块离它有
> 多远；**(3)** 已经补了什么、还差什么、下一期怎么做。
>
> 定位：这份文档是「harness 是资产，模型是租来的依赖」这个判断在本项目里的落地说明。
> 附带参考来源见文末。

---

## 一、什么是 harness：模型之外的六件事

Anthropic 对 agent 的定义刻意保守：**agent 就是「语言模型在循环里调用工具，根据环境反馈决定下一步」**。
模型只贡献一件事——每一步的推理；其余全部是常规软件工程。那「其余全部」就是 harness，
而它才是可靠性的来源。

归纳当前主流实践（LangGraph / OpenAI Agents SDK / Claude Agent SDK / CrewAI / MAF 等框架的分野、
以及多篇 2026 年的 harness 工程总结），一套 harness 由这些部件组成：

| # | 部件 | 职责 | 缺失时的典型症状 |
|---|------|------|------------------|
| 1 | **有界执行循环** | plan → act → observe → reflect，直到达成目标或触发停止条件 | 死循环、烧光额度、用户干等 |
| 2 | **工具层** | 参数校验、最小权限、沙箱、结构化错误、输出格式化、审计 | 工具一失败整轮崩掉；模型无法自纠 |
| 3 | **上下文管理** | 检索、压缩（compaction）、工具结果裁剪、just-in-time 取数 | 上下文腐化（context rot），越长越不准 |
| 4 | **记忆** | 工作 / 情景 / 语义 / 程序性四层，跨轮次跨会话 | 每次都从零开始，反复问同样的问题 |
| 5 | **状态持久化** | run/session 状态、断点续跑、成本与 token 计量 | 崩一次全丢，无法复盘 |
| 6 | **观测与评测** | 每一步轨迹（thought/action/args/result/latency）、可重放、CI 门槛 | bug 不可复现，只能靠重掷骰子调试 |

另有三条横切关注点：**权限**（在工具调用点强制，而不是写在提示词里）、**降级向诚实**
（失败时给更简单的路径 / 缓存结果 / 主动承认不知道，绝不猜）、**人闸门**（不可逆、大范围的动作用户确认）。

### 几条有共识的经验

- **预算必须有**：步数上限 + 成本/时间上限 + 进度检查，三者缺一都会空转。停止条件是新手最常忘的部分。
- **工具越少越好**：工具数超过约 30–50 个后，模型的选择准确率明显下降；有团队报告砍掉 80% 工具后
  效果反而变好。宁可少而通用，按需加载，也不要一次性注册一大片。
- **上下文是稀缺资源，不是仓库**：质量在窗口填满之前就开始下降。老的工具结果几乎永远不再被引用，
  是最便宜的一处压缩点。
- **子代理的本质是「上下文防火墙」**：它读 5 万 token、只回一页结论，省下的是编排线程的窗口，
  并行提速只是附赠。
- **记忆不是事实**：要当成提示。记忆说的和现场看到的不一致时，以现场为准；记忆还需要按
  用户/租户隔离，因为被投毒的记忆会持续影响后续每一次运行。

---

## 二、本项目现状 vs harness 应有部件

改造前的实际情况（`AssistantService.replyWithModel()` 内联实现）：

| 部件 | 改造前 | 判定 |
|------|--------|------|
| 执行循环 | 有雏形：内联 `for (round < 5)`，但硬编码在业务 Service 里 | ⚠️ 有循环，无 harness |
| 停止条件 | 只有步数（写死 5）；无时间预算、无进度判定 | ⚠️ |
| 并行工具调用 | 串行 `for (req : requests)`，逐个等待 | ❌ |
| 工具失败处理 | 工具抛异常 → 冒泡到 `sendDetailed` 的 catch → **整轮回复变成错误提示** | ❌ 严重 |
| 工具结果预算 | 原始结果直接进上下文，无裁剪 | ❌ |
| 上下文压缩 | 只有「最近 20 条原文」，无摘要/压缩 | ❌ |
| 长期记忆 | 无（跨会话不记得任何事实/偏好） | ❌ |
| 运行轨迹 | 只有安全审计（有没有被攻击），没有执行轨迹（这次怎么答的） | ❌ |
| 权限 | 角色下发工具 + `@ToolMemoryId` 服务端身份注入 + 工具内二次校验 | ✅ 已经很好 |
| 人闸门 | HITL 支付确认（一次性原子迁移 + 归属校验 + TTL） | ✅ 已经很好 |
| 评测 | 三层测试 + 端到端评测 + 红队变异引擎 + CI | ✅ 已经很好 |

结论：**权限、人闸门、评测三块已经达标**（这正是上一轮 JD 对照补齐的），
缺的是「循环韧性 + 上下文预算 + 观测 + 记忆」这几块。

---

## 三、第一期已实现：循环与工具层的韧性

### 3.1 新增 `harness` 包

| 文件 | 职责 |
|------|------|
| `harness/AgentLoop.java` | 执行循环本体：预算、停滞检测、并行调用、结果裁剪、轨迹产出 |
| `harness/Effectful.java` | 标记「效果型」工具类：必须留在调用线程执行 |
| `harness/RunTraceRepository.java` | 运行轨迹落库（失败不影响回答） |
| `api/RunTraceEntry.java`、`api/RunStepEntry.java` | 轨迹的只读 DTO |
| `db/migration/V6__create_assistant_run_trace.sql` | `assistant_run` + `assistant_run_step` 两张表 |

`AssistantService.replyWithModel()` 从「自己写循环」变成「组装上下文 → 交给 AgentLoop → 收结果」。

### 3.2 循环里具体做了什么

**(1) 有界循环 + 显式停止原因**

预算来自配置（`mall.assistant.harness.*`），不写死在代码里：

```yaml
harness:
  max-steps: 6                  # 最多几轮「模型 → 工具」
  timeout-ms: 30000             # 整轮墙钟上限
  max-tool-result-chars: 4000   # 单个工具结果进模型前裁剪
  max-same-tool-repeat: 2       # 同一调用允许重复次数
  max-parallel-tools: 4         # 并行只读工具线程数
  trace-enabled: true
```

退出原因永远显式返回，不再靠猜「模型是不是答完了」：

| stopReason | 含义 | 处理 |
|---|---|---|
| `DONE` | 模型给出最终文本 | 正常 |
| `MAX_STEPS` | 步数耗尽 | 回诚实兜底文案 + 写审计 `AGENT_MAX_STEPS` |
| `TIMEOUT` | 墙钟耗尽 | 同上，`AGENT_TIMEOUT` |
| `STALLED` | 同一调用原地打转 | 同上，`AGENT_STALLED` |

兜底文案统一走「降级向诚实」：**明确说没查完**，并给出可行动的建议（「可以拆开问我」），
绝不假装答完了。同时这类文案来自 harness，是可信文本，**不再过输出守卫**——否则会被自己的
规则误伤（这是项目里踩过的同款坑）。

**(2) 工具失败不再毁掉整轮**

原来工具抛异常会冒泡到最外层，整轮回复变成 "购物助手暂时无法回答（...）"。现在 `ToolRegistry.execute`
返回 `Execution(text, status)`，把异常转成模型能自纠的结构化错误：

```json
{ "error": "INVALID_STATE", "message": "只有待支付的订单才能发起支付确认",
  "hint": "这是可恢复的错误：请据此向用户说明原因，或换一个参数重试。" }
```

区分两类失败，处理方式不同：
- **业务异常**（订单不存在、状态不对）→ 把真实原因给模型，让它自己纠正或如实告知用户；
- **意外异常** → 服务端日志与审计留完整原因，给模型的只有能行动的信息（不要泄露内部实现）。

这直接对应 harness 的一条原则：**工具的错误是「环境反馈」，不是系统故障**。

**(3) 停滞检测（进度判定）**

同一工具 + 同一参数重复出现时，不再重复执行（结果不会变），而是回一条
`DUPLICATE_CALL` 说明，并注入一条纠偏提示；再犯就停止循环。

注意一个容易写错的细节：**重复调用也必须回一条 tool result 消息**，否则下一次模型调用会被
接口判为「工具调用没有对应结果」而直接报错——所以短路返回的是「不再执行」的说明，而不是空。

**(4) 并行工具调用——以及一个差点踩进去的坑**

同一轮里互不依赖的只读工具 fan-out 执行（固定小线程池，不用虚拟线程：工具里有 JDBC 与
`KnowledgeService` 的 `synchronized` 初始化，在 synchronized 块里阻塞会钉住载体线程）。

**但这个并行化差点造成一个静默 bug**：`PendingActionService` 用 `ThreadLocal` 把「当前会话 id」
传给工具层（有意为之——模型自报的会话 id 可被注入篡改，线程上下文不能）。如果把
`requestPayment` 扔到 worker 线程：

- 它**不会抛错**；
- 它只是在 `CURRENT_TURN.get()` 拿到 null，于是不记录会话关联；
- 结果是**支付确认卡片不再出现**，用户看到模型说「已发起支付」，页面却没有卡片。

静默失败比报错难查得多。所以引入 `@Effectful` 注解标记效果型工具类，这类调用一律留在调用线程：

```java
@Effectful("写库（创建待确认记录），且依赖调用线程的会话上下文")
@Component
public class PaymentTools { ... }
```

`ToolRegistry` 从注解推导出每轮工具包的效果型名单，`AgentLoop` 据此分流。
**加新写工具时必须标注**，否则它会以「看起来正常」的方式出错。

**(5) 上下文预算：只裁给模型看的那份**

超长工具结果进模型前裁剪（头保留 + 明确告知被截断），但**原始结果仍完整用于前端卡片渲染**。
「给模型的上下文」和「给界面的数据」是两个不同消费者，预算只该约束前者。

**(6) 运行轨迹**

`assistant_run`（一次运行的概要）+ `assistant_run_step`（每一步）落库，新增店员专属接口：

```
GET /api/assistant/runs?limit=20          # 最近的运行，含 stopReason / steps / wallMs
GET /api/assistant/runs/{runId}/steps     # 某次运行的逐步轨迹
```

`stopReason` 是这里最有价值的一列。审计回答「有没有人攻击」，轨迹回答「这次回答是怎么产生的、
为什么没答完」——排查 agent 行为靠的是后者。

观测层铁律：**轨迹写入失败绝不能影响回答**。所以在仓库里整体包了 try/catch，失败只降级为一条
warn 日志。把「记日志」写成会抛异常的强依赖，等于给自己埋一个「监控挂了业务也挂了」的故障。

### 3.3 系统提示补充：多步任务的处理方式

用户一次可能提多个请求（「帮我找找杯子，顺便看看我上周那笔订单」）。提示词里明确要求：
先拆子任务 → 互不依赖的查询一轮内同时发起 → 全部做完后**汇总成完整回答，不要只答一部分** →
某个子任务失败时其余照常回答并说明哪一项没拿到。

最后一条尤其重要：**部分失败不该退化成整体拒答**。

### 3.4 验证

见 `docs/验证记录.md` 附录 E。新增 `AgentLoopTest` 9 例（Mock 模型，离线确定性）：

| 用例 | 断言的核心性质 |
|------|----------------|
| 多工具一轮 | 两个独立工具都执行、结果按原序回填 |
| **效果型工具保上下文** | 与只读工具同轮时支付卡片不丢（上文的静默 bug 回归测试） |
| 工具分层 | `requestPayment` 是效果型，只读工具不是 |
| 工具失败自纠 | 参数绑定失败 → 结构化错误 → 模型第二轮看到 error+hint，最终仍给出回答 |
| 结果裁剪 | 给模型的是截断版，卡片数据来自完整版 |
| 停滞检测 | 一次执行 + 一次 DUPLICATE 后停止，回诚实兜底 + 写审计 |
| 预算耗尽 | 停止循环、只调一次模型、轨迹记 `MAX_STEPS` |
| 轨迹开关 | 关闭后不落库，回答照常 |
| 接口隔离 | 轨迹接口顾客 403 / 店员 200 |

---

## 四、第二期：记忆模块（✅ 已实现，见《导购闭环与记忆》）

> **实现状态**：本节设计已落地。工作记忆用滚动增量摘要（`assistant_conversation_summary` +
> `ConversationSummaryService`），语义记忆用 `assistant_user_memory` + `MemoryTools`
> （键白名单 / 写入过守卫 / 读出再检 / TTL 与条数上限 / 用户可查可删）。
> 情景记忆与程序性记忆按本节结论暂不做。实现细节与测试见
> [导购闭环与记忆](导购闭环与记忆.md)。

### 4.1 为什么不能只是「多留几条历史」

现在的记忆策略是「最近 20 条原文 + 超过就截断」。这有两个后果：
一，长会话必然爆窗；二，**被截断的部分永久丢失**，用户说「我上次跟你说过」时助手完全失忆。
再加上跨会话零记忆——同一个用户每次都要重新介绍自己。

### 4.2 四层记忆，每层解决不同问题

| 层 | 存什么 | 载体 | 生命周期 |
|---|---|---|---|
| **工作记忆** | 当前会话的活跃上下文 | 会话摘要 + 最近 N 条原文 | 单会话 |
| **情景记忆** | 发生过什么（时间线） | 已有 `assistant_message` 表 | 长期（可检索） |
| **语义记忆** | 关于用户的事实与偏好 | 新增 `assistant_user_memory` 表 | 长期，可更新 |
| **程序性记忆** | 可复用的方法/计划 | 暂不做（本项目场景价值低） | — |

### 4.3 工作记忆：压缩（compaction）而不是截断

触发条件按**窗口占用比例**，不按轮数（轮的长度差异极大）。

```
历史条数/估算 token 超过阈值
  → 用一次模型调用把较早的历史压成结构化摘要：
      已确认的决定 / 未解决的问题 / 关键约束 / 用户偏好
  → 摘要落库（会话级），上下文变成 [摘要] + [最近 N 条原文]
```

三条硬约束：

1. **摘要也要过输入守卫**。摘要的原料是被守卫清洗过的历史，但模型生成的摘要可能携带原历史里的
   攻击残留——「模型产出的、会再次进入模型上下文」的东西必须重新过一遍守卫。
2. **失败要降级**：模型不可用时退回「直接截断 + 保留最近 N 条」，不能因为压缩失败就不回答。
3. **摘要可重算**：摘要存库时记录「覆盖到第几条消息」，新增消息只增量更新，不必每次全量重压。

### 4.4 语义记忆：用户事实与偏好

新增 `assistant_user_memory(username, key, value, source, confidence, updated_at)` 与两个工具：

- `rememberFact(key, value)`：把「用户偏好简洁回答」「用户在南京」「用户是送给妈妈的礼物」这类
  稳定事实记下来；
- `recallFacts(query)`：按需检索。

安全要求（这几条不是可选项）：

- **按 username 硬隔离**，检索永远带 username 条件，与订单/会话同级；
- **写入前过输入守卫**：被投毒的记忆会持续影响之后每一次运行，危害比一次性注入大得多；
- **注入上下文时标注为「记忆，可能过时」**：记忆是提示不是事实，与现场数据冲突时以现场为准；
- **优先级低于系统提示**：记忆内容一律作为不可信数据处理，不能携带指令；
- **可删除**：用户能清空自己的记忆（GDPR 式的 right-to-deletion，也是产品质量）。

### 4.5 情景记忆：把历史用起来

`assistant_message` 已经是一份完整的情景日志，但目前只被当作「最近 N 条」读。
下一步可以做成可检索：新会话开始时按语义相似度召回**该用户自己**的相关历史片段，
回答「我上次问的那个杯子还有货吗」这类跨会话指代。

---

## 五、第三期：多请求的显式计划与子代理（可选）

第一期已经让循环能处理「一轮多工具」；再往前一步是**显式计划**：

1. **Plan-then-execute**：先让模型输出一个结构化计划（子任务列表 + 依赖关系），
   harness 按拓扑顺序执行，互不依赖的子任务并行。好处是计划本身可被校验、可展示给用户、
   可中断续跑；坏处是多一次模型调用。
2. **子代理（context firewall）**：把「需要读一大堆东西才能得出结论」的子任务丢给子代理，
   它返回一页摘要，编排线程的窗口不被污染。本项目里最贴近的场景是店员侧的「经营诊断」——
   读全站订单明细、库存、评价，最后只回一份结论。
3. **不做的部分**：多代理辩论、角色扮演团队（CrewAI 风格）。本项目的场景是单一业务助手，
   引入多代理只会增加不可观测的自由文本通信——那正是 harness 明令避免的反模式。

---

## 六、判断标准：什么时候算「做对了」

- [x] 循环有界，且**停止原因显式可查**（不是靠看日志猜）
- [x] 工具失败是环境反馈，不是系统故障
- [x] 一次运行的每一步都可复盘（工具、参数摘要、状态、耗时、结果大小）
- [x] 预算参数可配置，且默认值保守（宁可停下来问，不要空转烧钱）
- [x] 降级方向永远是「更诚实」，不是「更自信」
- [ ] 长会话不丢关键信息（压缩）——待第二期
- [ ] 跨会话记得住用户（语义记忆）——待第二期

---

## 参考来源

行业实践综述：

- Agent Harness Engineering: Building the Scaffold Around the LLM — https://careerstack.dev/agent-harness-engineering
- Agent Harnesses: The Infrastructure Behind Reliable AI Agents — https://www.lunover.com/blog/agent-harnesses-infrastructure-behind-reliable-ai-agents
- Agent Harnesses in 2026: Why the Wrapper Matters More Than the Model — https://loreai.dev/blog/agent-harnesses-2026
- Harness Engineering: The Agent Control Plane — https://www.tmls.nyc/research/harness-engineering
- Harness Engineering: Frameworks for Production AI Agents in 2026 — https://www.datasumi.com/blog/harness-engineering-frameworks

上下文工程：

- Context Engineering for AI Agents: The Complete Guide (2026) — https://toolhalla.ai/blog/context-engineering-ai-agents-2026
- Effective Context Engineering for AI Agents — https://vorstel.com/feeds/blog/effective-context-engineering-ai-agents
- Context Engineering: What Actually Belongs in the Window — https://www.techplained.com/context-engineering
- Architectural Paradigms and Best Practices for Coding Agents — https://kyle.pericak.com/wiki/research/coding-agent-best-practices/gemini.html

记忆架构：

- Letta vs Mem0 in 2026: Semantic and Episodic Memory for Agents — https://callsphere.ai/blog/vw6g-letta-mem0-semantic-episodic-memory-2026
- AI Memory Architectures Compared 2026 — https://presenc.ai/research/ai-memory-architecture-comparison-2026
- Knowledge Graphs for AI Agents: The Retrieval Architecture — https://thedatapraxis.com/blog/knowledge-graphs-for-ai-agents

框架横向对比：

- LangGraph vs CrewAI vs OpenAI Agents SDK 2026 — https://codecudos.com/blog/langgraph-vs-crewai-vs-openai-agents-sdk-2026
- Multi-Agent Frameworks Compared in 2026 — https://app-lab.ai/blog/multi-agent-frameworks-compared
- AI Agent Orchestration Frameworks Benchmark 2026 — https://tacavar.com/geo-ai-agent-orchestration-frameworks-benchmark-2026
