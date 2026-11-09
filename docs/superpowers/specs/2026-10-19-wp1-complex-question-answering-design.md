# WP-1：复杂问题回答设计（澄清 / 复合问题 / 证据覆盖）

- 日期：2026-10-19
- 范围：三条改造主线中的线 1 前半；线 2、线 3 和线 1 的版本/冲突部分不在本设计内
- 状态：待用户审阅

## 1. 背景与现状

当前聊天链路是 WebSocket → `ChatHandler.processMessage` → ReAct 循环（最多 4 轮、8 次工具）→ `search_knowledge` 等工具 → 流式最终回答。检索侧已有双路召回、RRF、重排序、父子块和引用持久化，本设计不改检索。

现状缺口：

- 没有澄清机制。只有检索结果为零时，提示词让模型“说明暂无相关材料并提示用户补充线索”（`LlmProviderRouter.java:164`），缺少“资料存在但答案取决于用户未提供的条件”这一场景。
- 复合问题只靠模型自发多次 tool call，没有子问题清单，也没有代码侧的证据覆盖检查。
- 提示词只要求“片段只覆盖一部分时只答一部分”（`LlmProviderRouter.java:163`），没有结构化的“哪部分没覆盖”输出。
- 工具注册表只有 `search_knowledge`、`generate_summary`、`submit_feedback`、`knowledge_stats`（`AgentToolRegistry.java:55`）。

本设计只解决以上四项；不改表、不改 ES mapping、不改 WebSocket 消息协议、不改检索排序。

## 2. 目标与非目标

### 目标

1. 复合问题先拆成带编号的子问题清单，再逐项检索。
2. 代码维护每个子问题的检索命中台账，最终回答后追加结构化的“子问题证据覆盖”。
3. 检索后仍缺少会改变结论的用户条件时，能提出 1-3 个具体问题并结束本轮。
4. 部分证据不足时，回答明确标注未覆盖部分，不编造依据。
5. 单问题与现有流程完全不变。

### 非目标

- 不实现线 3 的评测体系、评测集扩充或发布门禁。
- 不实现 WP-2 的版本/生效时间/蓝绿切换。
- 不实现 WP-4 的版本选择与资料冲突展示。
- 不实现 WP-3 的删除/权限一致性。
- 不做快捷回复按钮、不做澄清选项 UI。
- 不启用或修改 `CitationVerificationService`。
- 不调整检索参数、RRF 阈值或重排序。

## 3. 行为设计

### 3.1 三个工具

| 工具 | 参数 | 调用时机 | 结果 |
|---|---|---|---|
| `plan_question` | `subQuestions: string[2..3]` | 用户问题包含两个及以上可独立回答的部分 | 返回带编号的子问题清单，提示逐项检索 |
| `search_knowledge` | 原有 `query`、`topK`；新增可选 `subQuestionIndex: integer` | 已有计划时，每个子问题各调用一次 | 原有检索结果；代码按编号累计命中条数 |
| `ask_clarification` | `questions: string[1..3]` | 检索后仍缺少会改变结论的用户条件 | 返回给用户的问题列表；本轮立即结束 |

#### `plan_question` schema

```json
{
  "type": "object",
  "properties": {
    "subQuestions": {
      "type": "array",
      "items": { "type": "string" },
      "minItems": 2,
      "maxItems": 3,
      "description": "可以独立检索和回答的子问题，按用户关心的顺序排列。"
    }
  },
  "required": ["subQuestions"],
  "additionalProperties": false
}
```

工具描述要点：只在复合问题时调用；每个子问题应能单独检索和回答；调用后必须为每个子问题分别调用 `search_knowledge` 并填写 `subQuestionIndex`。

校验：去掉首尾空白后，2-3 个、非空、不重复；否则抛参数错误，让模型重试。

返回给模型的 content 示例：

```text
已记录 3 个子问题：
1. 出差住宿标准是多少？
2. 酒店超标如何审批？
3. 报销流程是什么？

请分别调用 search_knowledge 检索每个子问题，并在 subQuestionIndex 填写对应编号；全部子问题检索后再给出最终回答。
```

#### `search_knowledge` 新增参数

```json
{
  "subQuestionIndex": {
    "type": "integer",
    "minimum": 1,
    "maximum": 3,
    "description": "如果本轮已调用 plan_question，填写当前检索对应的子问题编号（从 1 开始）；单一问题不填。"
  }
}
```

`required` 仍只有 `["query"]`。

#### `ask_clarification` schema

```json
{
  "type": "object",
  "properties": {
    "questions": {
      "type": "array",
      "items": { "type": "string" },
      "minItems": 1,
      "maxItems": 3,
      "description": "向用户提出的具体问题；每个问题应说明用途，便于用户回答。"
    }
  },
  "required": ["questions"],
  "additionalProperties": false
}
```

工具描述要点：只有在检索后仍缺少会改变结论的用户条件（所在地区、员工职级、适用版本、时间范围等）时调用；不要询问资料或对话里已有的信息；不要用“请补充更多信息”这类泛泛问题；不要用澄清代替检索；调用后本轮结束。

返回给用户的 content 示例：

```text
为了给出准确结论，还需要你确认以下信息：
1. 你所在的城市是哪里？（用于匹配当地住宿标准）
2. 你的员工职级是？
```

### 3.2 计划与覆盖台账

在 `ChatHandler` 内新增每个生成任务的状态，台账本身放在同包的独立 `QuestionPlan` 类里，便于单测：

```java
private final Map<String, QuestionPlan> generationPlans = new ConcurrentHashMap<>();

// QuestionPlan（同包独立类）职责：
// - subQuestions：规范化后的子问题清单
// - hits[i]：子问题 i+1 的累计命中条数；-1 表示尚未检索
// - footerAppended：覆盖说明是否已追加
```

规则：

1. `plan_question` 成功执行后，把工具结果 `data.subQuestions` 存入 `generationPlans`。同一生成任务重复调用 `plan_question` 直接返回工具错误，不覆盖原计划。
2. 计划存在时，`search_knowledge` 必须带 `subQuestionIndex`，且必须在 1-计划长度之间；否则返回工具错误，不执行检索。没有计划却带了编号，同样返回错误，提示先调用 `plan_question`。
3. 命中条数取该次检索返回的结果条数（父子块展开后的 `results.size()`）；同一子问题多次检索时累加。这里的“命中”是检索命中的片段数，不是最终回答实际引用的数量。
4. 计划只在当前生成任务内有效。生成结束、澄清、取消或失败时，在 `cleanupGenerationState` 中移除。
5. 计划最多 3 个子问题：现有 ReAct 最多 4 轮，串行检索时 3 个子问题加上计划轮刚好用满，最终回答由轮次用尽后的无工具收尾完成；4 个会在串行情况下挤掉检索轮次。

### 3.3 澄清立即收尾

`ask_clarification` 成功执行后：

1. `ChatHandler.executeToolForReAct` 把工具返回的问题文本通过 `appendStreamChunk` 写入当前助手消息；
2. 返回 `ExecutedToolResult(content, true)`，复用现有 `streamedToUser` 收尾路径，立即 `finalizeResponse`，不再请求模型；
3. 澄清工具本身不产生新的引用；如果同一轮此前已经检索过，沿用既有引用映射，不做特殊清理；
4. 用户回答后是一条新的聊天消息，进入正常生成流程；
5. 本轮已登记的计划随生成清理，不跨轮保留。

与 `generate_summary` 共用同一条“工具已流式输出 → 立即收尾”的机制，但 `ask_clarification` 不经过 `toolChunkConsumer`，由 `ChatHandler` 直接写入。

### 3.4 覆盖说明

只在同时满足以下条件时追加：

- 当前生成任务存在计划；
- 走到正常最终回答分支，或 ReAct 轮次/时间预算用尽后的无工具收尾分支；
- 当前已累积的回答内容非空；
- 尚未追加过。

格式（追加到最终回答末尾，通过 `appendStreamChunk` 流式输出，因此会随回答一起持久化）：

```text

---

**子问题证据覆盖**
- 子问题 1（出差住宿标准是多少？）：命中 5 条
- 子问题 2（酒店超标如何审批？）：命中 0 条，资料未覆盖
- 子问题 3（报销流程是什么？）：未检索，资料未覆盖
```

规则：

- 命中数为 0 显示“命中 0 条，资料未覆盖”；
- 从未检索显示“未检索，资料未覆盖”；
- 所有子问题都有命中时仍输出清单，便于核对；
- 不使用 `[N]` 编号，避免与引用编号混淆；
- 模型最终回答为空时不追加，保持现有“模型未返回有效内容”错误路径；
- `ask_clarification` 和 `generate_summary` 的流式收尾不追加覆盖说明。`generate_summary` 内部自己检索，不经过子问题台账。

### 3.5 提示词调整

在 `LlmProviderRouter.buildReActMessages` 的现有规则之后新增两段，并修改两处已有规则：

1. 现有“每一个用户问题都必须先调用 search_knowledge”改为：
   - 复合问题先调用 `plan_question` 制定计划，再逐项调用 `search_knowledge`；
   - 其他问题仍必须先调用 `search_knowledge`。
2. 现有“只有工具明确返回零片段时，才说明暂无相关材料”改为：
   - 只有所有检索都返回零片段时，才说明暂无相关材料；
   - 部分子问题没有证据时，只回答有证据的部分，并明确说明其余部分资料未覆盖。
3. 新增“复合问题与证据覆盖”规则：
   - 两个及以上可独立回答的部分 → `plan_question`（2-3 个）；
   - 单一问题不要调用；
   - 每个子问题分别检索并填写 `subQuestionIndex`，不要把多个子问题塞进一次检索；
   - 没有证据的子问题不得说成已解决，不得硬凑来源编号。
4. 新增“澄清”规则：
   - 检索后仍缺少会改变结论的用户条件 → `ask_clarification`（1-3 个），问题里说明用途；
   - 不问资料或对话中已有的信息，不用泛泛的“请补充更多信息”，不用澄清代替检索；
   - 调用后本轮结束，等待用户回答。

提示词只负责“何时调用”，代码负责“计划/编号/覆盖说明”的事实记录，避免提示词承诺没有代码兑现。

### 3.6 前端

`frontend/src/views/chat/modules/chat-message.vue` 的 `toolNameLabels` 增加：

```ts
plan_question: '拆解问题',
ask_clarification: '请求补充信息'
```

其余不改。澄清问题作为普通助手消息渲染；覆盖说明是普通 Markdown；工具事件通过现有 `tool_call` 事件展示。WebSocket 协议、消息类型、类型定义都不变。

## 4. 代码改动点

| 文件 | 改动 |
|---|---|
| `service/AgentToolRegistry.java` | 注册 `plan_question`、`ask_clarification`；`search_knowledge` schema 增加 `subQuestionIndex`；两个新工具的参数解析与格式化 |
| `service/ChatHandler.java` | 新增 `generationPlans` 台账；`executeToolForReAct` 中校验编号、记账、澄清提前收尾；最终回答前追加覆盖说明；清理台账；日志 |
| `service/LlmProviderRouter.java` | 修改两条已有规则，新增复合问题与澄清规则 |
| `frontend/src/views/chat/modules/chat-message.vue` | 工具名标签两行 |
| `src/test/...` | 新增工具注册表测试和 ChatHandler 两条链路测试；现有测试作为回归 |

不改：`FileUpload`、`DocumentVector`、ES mapping、`ddl.sql`、WebSocket 协议、检索/重排代码、评测集。

## 5. 边界与失败处理

| 场景 | 行为 |
|---|---|
| `plan_question` 少于 2、多于 3、空串、重复 | 工具参数错误，模型可重试；不中断生成 |
| 计划存在但检索未填编号 | 工具错误，提示填写 `subQuestionIndex`；不执行检索 |
| 没有计划却填编号 | 工具错误，提示先调用 `plan_question`；不执行检索 |
| 编号越界 | 工具错误，提示有效范围；不执行检索 |
| 重复调用 `plan_question` | 工具错误，保留原计划 |
| 只检索了部分子问题 | 正常回答；覆盖说明把未检索项标为“未检索，资料未覆盖” |
| 所有子问题都 0 命中 | 走现有“暂无相关材料”提示；覆盖说明列出全部未覆盖 |
| 模型不调用 `plan_question` | 退回现有单次/多次检索行为，不追加覆盖说明；不伪造覆盖结论 |
| 最终回答为空 | 不追加覆盖说明，走现有错误收尾 |
| 截止时间/工具预算用尽 | 现有收尾逻辑优先；若已累积回答，追加覆盖说明；不再额外调模型 |
| 用户点击停止/生成被取消 | `appendStreamChunk` 的取消检查生效；不追加覆盖说明 |
| 调用 `ask_clarification` | 立即流式输出问题并收尾；不产生引用；计划随生成清理 |
| 计划后调用 `generate_summary` | 走原有流式摘要收尾；不追加覆盖说明（已知边界） |
| 断线重连 | 覆盖说明通过 `chatGenerationStateService.appendChunk` 写入生成态，重连可恢复 |

## 6. 测试与验收

### 6.1 新增单测

1. `AgentToolRegistryPlanClarificationTest`
   - `plan_question` 返回编号清单，`data.subQuestions` 正确；
   - 少于 2、多于 3、空串、重复 → 参数错误；
   - `ask_clarification` 1-3 个问题的格式化正确，0 个或 4 个报错；
   - `search_knowledge` 的 schema 包含 `subQuestionIndex`。
2. `ChatHandlerPlanCoverageTest`（复用 `ChatHandlerDeadlineTest` 的本地 HTTP 假模型方式）
   - 第一轮 `plan_question`（2 个子问题），第二轮两个 `search_knowledge`（编号 1 返回 2 条，编号 2 返回 0 条），第三轮最终回答；
   - 断言：最终内容包含“子问题证据覆盖”、编号 1 命中 2 条、编号 2 “命中 0 条，资料未覆盖”；
   - 断言：`ConversationService.recordConversation` 持久化的内容包含覆盖说明；
   - 断言：编号 2 的检索没有产生引用映射。
3. `ChatHandlerClarificationTest`
   - 第一轮 `ask_clarification`（1 个问题）；
   - 断言：只发生 1 次模型请求；问题文本通过 `chunk` 下发；`recordConversation` 持久化该问题；没有第二轮模型请求；完成事件为 `finished`。
4. `ChatHandlerPlanValidationTest`
   - 未计划先填编号 → 模型收到工具错误，不执行检索；
   - 重复计划 → 保留原计划；
   - 编号越界 → 工具错误。

### 6.2 回归

- `ChatHandlerDeadlineTest`、`ChatHandlerReferenceNumberingTest` 必须原样通过（单问题路径不变）。
- 全量 `mvn test`；按仓库现有方式映射 MinIO 密钥。测试仍不需要外部 MySQL/ES/Redis。
- 现有 50 题离线检索评测可做一次 `retrievalOnly` 回归，确认检索代码未受影响；不新增题目、不改指标。

### 6.3 手工验收

1. 在浏览器里问一个复合问题（例如“住宿标准是什么，超标怎么审批？”）：
   - 工具事件出现“拆解问题”；
   - 每个子问题各一次“检索知识库”；
   - 最终回答末尾出现“子问题证据覆盖”；
   - 引用编号可点击，详情正常。
2. 问一个缺少关键条件的问题（例如“住宿标准是多少？”且资料按城市区分）：
   - 出现“请求补充信息”；
   - 助手消息是 1-3 个具体问题；
   - 回答本轮结束，不继续生成；
   - 用户回答后能继续正常问答。
3. 问一个单一问题：
   - 行为与改动前一致，无计划、无覆盖说明。
4. 观察 WS 事件、最终消息、MySQL 历史记录和引用详情；如本地资料不适合，临时上传 1-2 份测试文档，验收后删除。

## 7. 风险与限制

- **模型不调用计划**：代码无法强制模型先计划。此时退回现有行为，不追加覆盖说明；这是有意的“不伪造覆盖”，不是遗漏。
- **多一轮模型调用**：复合问题会比现在多一轮（计划），延迟增加；只对复合问题生效，单问题不受影响。
- **编号映射依赖模型**：代码只校验编号范围，不校验编号与子问题语义是否匹配；计划工具返回的编号清单是唯一依据。
- **覆盖 ≠ 答案正确**：命中数只表示检索到片段，不表示最终回答正确引用了它们；不改变现有引用校验的定位。
- **轮次上限**：计划最多 3 个子问题，避免串行检索挤掉最终回答；模型若坚持串行且超时，按现有截止时间收尾。
- **无评测集**：本次不新增评测题，复杂问题的行为质量靠单测 + 手工场景验证；线 3 再做持续评测。

## 8. 明确不做

- 版本号、生效时间、蓝绿索引切换、删除墓碑、权限 epoch、冲突差异展示。
- 评测集扩充、发布门禁、延迟/成本指标。
- 快捷回复按钮、澄清选项 UI。
- 引用校验启用、重排序策略调整。

## 9. 与已批准设计的差异

子问题数量上限从讨论时的 4 个收敛为 3 个：现有 ReAct 循环最多 4 轮，模型若串行检索，4 个子问题会挤掉最终回答轮次；3 个可以稳定走完“计划 + 检索 + 收尾”。其余与已批准设计一致。
