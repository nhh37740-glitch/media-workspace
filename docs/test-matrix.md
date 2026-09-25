# 测试用例与执行结果

本文件是 05 号规范用例矩阵在实现仓库中的对应记录。**状态只有两种来源：实际运行得到的报告，
或尚未运行。** 没有运行过的用例一律标 NOT_RUN，不因为“相关代码已经写好”而标 PASS。

证据目录：`evidence/<commit>/<runId>/`，由 `scripts/collect-evidence.sh` 生成。
测试命令见 `docs/runbook.md`。

## 状态说明

| 状态 | 含义 |
|---|---|
| PASS | 有实际运行的报告，断言全部通过 |
| FAIL | 实际运行过，存在失败断言 |
| BLOCKED | 环境或依赖不具备，无法运行，且已记录原因 |
| NOT_RUN | 尚未运行 |

## 单元与集成测试（Gradle）

| 用例 | 级别 | 实现位置 | 状态 |
|---|---|---|---|
| JOB-01 状态机穷举 | U/P0 | `media-domain/src/test/.../TaskStateMachineTest` | PASS |
| JOB-01 上传状态机 | U/P0 | `media-domain/src/test/.../UploadStateMachineTest` | PASS |
| JOB-04 退避阶梯 | U/P0 | `media-domain/src/test/.../BackoffPolicyTest` | PASS |
| PROC-02 永久错误分类 | U/P0 | `media-domain/src/test/.../FailureClassifierTest` | PASS |
| ACL-02/ACL-04 角色矩阵 | U/P0 | `media-domain/src/test/.../RolePolicyTest` | PASS |
| SHARE-02 分享有效期 | U/P0 | `media-domain/src/test/.../ShareWindowTest` | PASS |
| UP-02/UP-04 分片几何 | U/P0 | `media-domain/src/test/.../ChunkGeometryTest` | PASS |
| MOD-01 架构依赖方向 | U/P0 | 根构建任务 `architectureCheck` | PASS |
| MOD-01 版本锁定 | U/P0 | 根构建任务 `versionLockCheck` | PASS |
| UP-11 存储根目录约束 | I/P0 | `adapter-storage/src/test/.../LocalMediaStorageTest` | PASS |
| UP-11 符号链接越界 | I/P0 | 同上 | PASS |
| PROC-01 真实转码（含竖屏/720p/放大抑制） | I/P0 | `adapter-transcode/src/integrationTest/.../ProcessTranscoderIT` | PASS |
| PROC-02 损坏与伪装的输入 | I/P0 | 同上 | PASS |
| PROC-03 stderr 洪水与进度解析 | I/P0 | 同上 | PASS |
| PROC-04 超时与取消终止进程树 | I/P0 | 同上 | PASS |
| PROC-05/PROC-06 半成品与非零退出 | I/P0 | 同上 | PASS |
| PROC-07 文件名含 shell 字符 | F/P1 | 同上 | PASS |
| MSG 主题前缀解析 | U/P0 | `adapter-messaging/src/test/.../TopicNamesTest` | PASS |
| MSG 生产者与消费者主题一致 | U/P0 | `adapter-messaging/src/test/.../KafkaEventPublisherTest` | PASS |
| UP-07 配额预留并发竞争 | I/P0 | `adapter-persistence/src/integrationTest/.../WorkspaceRepositoryIT` | PASS |
| ACL-01/02 成员可见性 | I/P0 | 同上 | PASS |
| DATA-01 空库迁移 | I/P0 | 各集成测试启动时用 Flyway 迁移新 schema | PASS |

**尚未实现的 P0/P1 用例**（见下方“未完成事项”）：UP-01、UP-03、UP-05、UP-06、UP-08~UP-10、
UP-12、MSG-01~MSG-07、JOB-02、JOB-03、JOB-05~JOB-09、PLAY-01~PLAY-03、SHARE-01、SHARE-03、
SSE-01、SSE-02、LOG-01~LOG-03、DATA-02~DATA-04、LOAD-01、LOAD-02、AUTH-01~AUTH-03、
ACL-03、CI-01~CI-04 的自动化形式尚未全部落地。

其中一部分已由 `scripts/smoke-test.sh` 以端到端方式覆盖（见下节），但那条链路是冒烟级别的，
不替代针对性用例，因此这里不把对应 ID 标为 PASS。

## 端到端（冒烟脚本实际执行的链路）

`scripts/smoke-test.sh` 在每次部署后运行，退出码非 0 即视为部署失败。

| 步骤 | 覆盖的验收点 | 状态 |
|---|---|---|
| 健康与就绪 | CI-03（部分） | PASS |
| 登录并取得会话 | AUTH-01（正确密码分支） | PASS |
| 创建空间 | — | PASS |
| 分片上传真实视频 | UP-01、UP-02（单分片与多分片路径） | PASS |
| 同幂等键同体重放返回同一会话 | UP-08（同体分支） | PASS |
| complete 并等待 Worker 完成 | UP-01、PROC-01、MSG-01（端到端） | PASS |
| 全量下载与 Range 请求 | PLAY-01、PLAY-02（单范围分支） | PASS |
| 封面 | — | PASS |
| 创建分享、兑换、播放、撤销后拒绝 | SHARE-01、SHARE-02 | PASS |
| 登出后会话失效 | AUTH-02 | PASS |

## 未完成事项（诚实记录）

1. **msg/job 系列故障用例没有自动化形式。** MSG-02/03/04/05、JOB-03/05/07/08 需要注入故障
   （提交后杀进程、屏障并发、旧 epoch 抢写），计划用 `tests/e2e` 下的故障脚本实现，目前
   只有领域层与仓储层的单元/集成测试覆盖了其中的规则部分，端到端的故障注入未实现。
2. **消息幂等与 DLQ 的集成测试未运行。** 消费者代码已实现（inbox 去重、poison 落库后再确认、
   DLQ outbox），但针对真实 Kafka 的重放测试尚未编写，因此 MSG-01/03/04/05 标 NOT_RUN。
3. **DATA-02 的 EXPLAIN 对比未做。** 索引已按 04 号规范建立，但没有记录一次实际的执行计划对比。
4. **LOAD-01/LOAD-02 未做。** 容量上限逻辑已实现（`capacity_counter` 行锁 + `429`/`CAPACITY_WAIT`），
   但没有做持续提交的负载实验，也没有两 Worker 与单 Worker 的对比数据。
5. **LOG-01 的 trace 串联未做端到端验证。** 结构化日志包含 traceId/taskId/generation 等字段，
   但没有写一条断言把 B01..B09 串起来的测试。
6. **DATA-04 备份恢复演练未做。**
7. **AUTH-03（缺 CSRF 拒绝）与 ACL-03（移除成员后关闭 SSE）未写成自动化测试。**
   两者都已实现：CSRF 由框架强制，SSE 每次轮询重新校验成员资格。

## 与 05 号规范的差异

- **未使用 Testcontainers。** 演示主机没有容器引擎，规范允许改用“明确隔离的 Compose 测试栈”。
  这里的做法是：每次集成运行创建独立的 `mw_it_<random>` schema 与独立的存储目录，测试结束即删除，
  绝不接触演示库。理由与实测记录见 `docs/decisions.md`。
- **集成测试的 Kafka 隔离用主题前缀实现**，而非独立 broker；消费组名同样加前缀。
