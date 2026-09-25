# CONTRACT_CHANGE-001：TaskRepository 增加 recoverLapsed

日期：2026-09-25。触发用例：JOB-07（处理时 Worker 退出，重启后任务不应永远停在 RUNNING）。

## 背景与定位

`tests/e2e` 的 `StaleExecutionIT` 复现了租约过期后的恢复路径，两条用例都失败在
`recoverLapsedLeases` 返回 0（一个任务都没恢复）。

沿数据流定位，首个违约边界是 **B04（application → persistence）**：

- `TaskRecoveryService` 调用既有的 `TaskRepository.fail(...)` 来记录结果；
- `TaskRepositoryAdapter.fail` 走 `TaskMapper.failTask`，其 `WHERE` 子句包含
  `lease_until > UTC_TIMESTAMP(6)`；
- 而恢复的前提正是**租约已过期**，于是条件更新影响 0 行，`fail` 返回 false，
  服务层判定"更新未生效"并放弃；
- 任务因此永远停在 RUNNING，`task_attempt` 也不会被关闭。

责任模块有两个候选：

- **media-application**：把一个"执行仍然有效"的操作（`fail`）用在了租约已失效的场景上；
- **adapter-persistence**：`failTask` 的 `WHERE` 子句是为"执行仍持有租约"设计的，
  这是它正确的语义，不该为恢复放宽。

根因是**缺少一个操作**，而不是某一侧写错：恢复需要一个"租约已失效时写入"的语句，
它在功能上与 `fail` 不同，放宽 `fail` 会让"只有持有有效租约的执行才能写结果"这条不变量失效。

## 契约变更

新增方法，不修改既有方法：

```java
// media-application: TaskRepository
boolean recoverLapsed(TaskLease lease, TaskErrorCode errorCode, String errorSummary,
                      Duration retryDelay, boolean terminal);
```

语义：

- 与 `fail` 的效果一致：更新任务状态、关闭 attempt、终态时释放容量并写结果事件；
- 与 `fail` 的差别只有一处：`WHERE` 子句**不包含** `lease_until > now`；
- 仍然要求完整的执行身份（taskId、generation、executionEpoch、state='RUNNING'），
  因此只会影响当前真正持有该行的执行；若期间已被重新领取，epoch 已变，更新影响 0 行；
- attempt 记为 LOST 而不是 FAILED：租约失效只能说明该 Worker 不再续期，
  不能断言它没有写出文件。

## 兼容性

- **旧方法未改动**：`fail` 的签名与语义完全不变，已有调用方不受影响。
- **新增方法**：实现方必须实现它；本仓库只有 `TaskRepositoryAdapter` 一个实现，
  已同步实现。
- **数据库无变更**：不新增表或列，不需要迁移，schema 版本不变。
- **事件 schema 无变更**：`TASK_FAILED` 的结构未动。

## 分阶段提交

规范要求"每个实施提交仍只改一个模块"。本次无法满足，原因是接口与其唯一实现必须同时改动，
否则编译不通过（`TaskRepositoryAdapter` 会给不出抽象方法的实现）。

因此本次**合并为一个提交**，跨 `media-application`（接口）与 `adapter-persistence`
（语句与实现）两个模块，并在此记录。合并范围严格限定为：

- `media-application`：`TaskRepository` 新增一个方法；`TaskRecoveryService` 改调它。
- `adapter-persistence`：`TaskMapper` 新增一条语句、XML 新增对应 update、
  `TaskRepositoryAdapter` 实现新方法。

没有顺带修改任何其他契约、DTO、事件字段或根构建。

## 影响模块与验证

| 模块 | 影响 |
|---|---|
| media-application | 端口新增方法；恢复服务改调新方法 |
| adapter-persistence | 新增 SQL 语句与实现；既有语句未改动 |
| media-api / media-worker | 只通过端口使用，无需改动 |

验证：

- `StaleExecutionIT` 的 JOB-07 与 JOB-03 两条用例（修改前失败，修改后通过）；
- 既有 `TaskRepositoryAdapter.fail` 的全部用例保持通过，证明未放宽原不变量；
- `./gradlew :adapter-persistence:integrationTest`、`:tests:e2e:integrationTest` 全量重跑。
