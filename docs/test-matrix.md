# 测试用例与执行结果

本文件是 05 号规范用例矩阵在实现仓库中的对应记录。**状态只有两种来源：实际运行得到的报告，
或尚未运行。** 没有运行过的用例一律标 NOT_RUN，不因为"相关代码已经写好"而标 PASS。

证据收集方法见 `docs/runbook.md` 第 4 节；Jenkins 结果见第六节，本地运行记录见第八节。

## 状态说明

| 状态 | 含义 |
|---|---|
| PASS | 有实际运行的报告，断言全部通过 |
| FAIL | 实际运行过，存在失败断言 |
| BLOCKED | 环境或依赖不具备，无法运行，且已记录原因 |
| NOT_RUN | 尚未运行 |

## 一、单元测试（`./gradlew check`，不启动外部服务）

| 用例 | 级别 | 实现位置 | 状态 |
|---|---|---|---|
| JOB-01 任务状态机穷举（含终态吸收、进度不可覆盖终态） | U/P0 | `media-domain/.../TaskStateMachineTest` | PASS |
| JOB-01 上传状态机 | U/P0 | `media-domain/.../UploadStateMachineTest` | PASS |
| JOB-04 退避阶梯与抖动 | U/P0 | `media-domain/.../BackoffPolicyTest` | PASS |
| PROC-02 永久错误分类 | U/P0 | `media-domain/.../FailureClassifierTest` | PASS |
| ACL-02 / ACL-04 角色矩阵与上传归属 | U/P0 | `media-domain/.../RolePolicyTest` | PASS |
| SHARE-02 分享有效期窗口 | U/P0 | `media-domain/.../ShareWindowTest` | PASS |
| UP-02 / UP-04 分片几何 | U/P0 | `media-domain/.../ChunkGeometryTest` | PASS |
| MOD-01 依赖方向（domain 无框架、application 不依赖 adapter、adapter 不依赖可执行 JAR） | U/P0 | 根构建 `architectureCheck` | PASS |
| MOD-01 版本锁定（无动态版本，构建脚本与版本目录一致） | U/P0 | 根构建 `versionLockCheck` | PASS |
| UP-11 存储键与根目录约束（含符号链接越界、拒绝覆盖异内容） | U/P0 | `adapter-storage/.../LocalMediaStorageTest` | PASS |
| MSG 主题前缀解析 | U/P0 | `adapter-messaging/.../TopicNamesTest` | PASS |
| MSG 生产端与消费端主题名一致 | U/P0 | `adapter-messaging/.../KafkaEventPublisherTest` | PASS |
| MSG 监听器注册归属（worker 消费请求、api 消费结果） | U/P0 | `media-api`/`media-worker` 各自的 `ListenerRegistrationTest` | PASS |

## 二、集成测试（真实 MySQL / Kafka / FFmpeg，每次运行独立 schema）

| 用例 | 级别 | 实现位置 | 状态 |
|---|---|---|---|
| PROC-01 真实转码（竖屏、720p、不放大小、无声源、封面校验） | I/P0 | `adapter-transcode/.../ProcessTranscoderIT` | PASS |
| PROC-02 损坏与伪装输入的永久拒绝 | I/P0 | 同上 | PASS |
| PROC-03 stderr 洪水不阻塞、尾部有界、进度解析 | I/P0 | 同上 | PASS |
| PROC-04 截止时间终止与取消终止自有进程树 | I/P0 | 同上 | PASS |
| PROC-05 / PROC-06 半成品与"退出 0 但无产物"均不发布 | I/P0 | 同上 | PASS |
| PROC-07 文件名的引号与 shell 字符不被执行 | F/P1 | 同上 | PASS |
| UP-07 配额预留的并发竞争（行锁串行化） | I/P0 | `adapter-persistence/.../WorkspaceRepositoryIT` | PASS |
| ACL-01 / ACL-02 非成员不可见、成员角色约束 | I/P0 | 同上 | PASS |
| DATA-01 空库迁移（每个集成测试用 Flyway 迁移新 schema） | I/P0 | 全部集成测试的启动路径 | PASS |

## 三、故障注入与跨模块（`tests/e2e`）

| 用例 | 级别 | 实现位置 | 状态 |
|---|---|---|---|
| MSG-01 同一事件投递 10 次只产生一次状态转换 | I/P0 | `DuplicateRequestEventIT` | PASS |
| MSG-01 同 taskId 的第二个事件不重复转换 | I/P0 | 同上 | PASS |
| MSG-01 已取消任务不被迟到事件复活 | I/P0 | 同上 | PASS |
| MSG-05 同 eventId 异体被隔离而非应用 | I/P0 | 同上 | PASS |
| JOB-02 两个 Worker 不能同时持有同一任务 | I/P0 | `StaleExecutionIT` | PASS |
| JOB-03 租约过期后新执行发布，旧执行发布被拒、指针仍指向新产物 | F/P0 | 同上 | PASS |
| JOB-03 旧执行不能把已成功的任务改成失败 | F/P0 | 同上 | PASS |
| JOB-07 Worker 消失后任务不永远停在 RUNNING，attempt 记为 LOST | F/P0 | 同上 | PASS |
| JOB-05 取消先提交则任务保持 CANCELLED 且不发布 | I/P0 | `CancellationRaceIT` | PASS |
| JOB-05 成功先提交则后续取消是冲突而非静默回退 | I/P0 | 同上 | PASS |
| JOB-05 取消与发布并发只产生一个合法终态 | I/P0 | 同上 | PASS |
| JOB-08 素材删除后任务取消、旧结果被拒、素材不可见 | I/P0 | 同上 | PASS |
| JOB-09 容量上限下重试被拒绝而非超额 | I/P0 | `CapacityBoundIT` | PASS |
| JOB-09 计数器随任务生命周期增减，终态只释放一次 | I/P0 | 同上 | PASS |
| JOB-04 每代最多 3 次执行，用尽后 FAILED 且释放容量 | I/P0 | 同上 | PASS |
| LOAD-01 并发准入不超过上限 | F/P0 | 同上 | PASS |
| JOB-09 计数器与任务表不一致时**报告**而不静默修正 | I/P0 | 同上 | PASS |

## 四、端到端（HTTP 经 Nginx，`scripts/smoke-test.sh`）

每次部署后运行，退出码非 0 即视为部署失败。

| 步骤 | 覆盖的验收点 | 状态 |
|---|---|---|
| 健康与就绪 | CI-03（部分） | PASS |
| 登录并取得会话 | AUTH-01（正确密码分支） | PASS |
| 创建空间 | — | PASS |
| 分片上传真实视频（单分片与多分片路径） | UP-01、UP-02 | PASS |
| 同幂等键同体重放返回同一会话 | UP-08（同体分支） | PASS |
| complete 并等待 Worker 完成 | UP-01、PROC-01、MSG-01（端到端） | PASS |
| 全量下载与 Range 请求 | PLAY-01、PLAY-02（单范围分支） | PASS |
| 封面 | — | PASS |
| 创建分享、兑换、播放 | SHARE-01 | PASS |
| 撤销后新请求被拒 | SHARE-02 | PASS |
| 越会话 CSRF 令牌被拒绝 | AUTH-03（部分） | PASS |
| 登出后会话失效 | AUTH-02 | PASS |

## 五、仍未运行（NOT_RUN）

1. **MSG-02 / MSG-03 / MSG-04 / MSG-06**：outbox 确认后崩溃重发、消费提交后确认前退出、
   消费回滚不提交 offset、Kafka 停机期间完成上传。需要真实 Kafka 的重放与进程终止编排，
   消费者代码已实现（inbox 去重、poison 落库后再确认、DLQ outbox），但没有对应测试。
2. **JOB-06** 同重试键并发重试只 +1 代：幂等逻辑已实现，无并发测试。
3. **PLAY-03** 浏览器拖动播放：需要浏览器自动化。
4. **SSE-01 / SSE-02** 断线重连与慢客户端：SSE 已实现（快照、版本号去重、有界队列、
   每次轮询重查权限、15 秒心跳），但没有自动化测试。
5. **LOG-01 ~ LOG-03**：结构化日志字段已按规范输出，但没有写把 B01..B09 串起来的断言测试。
6. **DATA-02** 万条数据下的 EXPLAIN 对比：索引按 04 号规范建立，未做执行计划对比。
7. **DATA-03** GC 与有效租约并发的删除保护：GC 已实现并在删除前复核数据库引用，无并发测试。
8. **DATA-04** 备份恢复演练。
9. **LOAD-02** 两 Worker 与单 Worker 的吞吐对比。
10. **ACL-03** viewer 已开 SSE 后被移出成员：实现上每次轮询都重查成员资格，无自动化测试。
11. **MOD-02 / MOD-03 的 Jenkins 拒绝场景**：门禁脚本本身可用且有单元测试
    （计划书 `scripts/test_change_scope.py` 的 8 项）。Jenkins build #9 的 ScopeGate 已通过，
    但跨模块、超文件数或根依赖修改的拒绝场景尚未通过 Jenkins 执行。
12. **CI-02 / CI-04 的故障注入场景**：尚未执行；CI-01 / CI-03 的通过证据见下节。

## 六、Jenkins 流水线

Jenkins build #9 的 `build.xml` 记录 SUCCESS，HEAD 为
`629a71f1a720b52b7d2956d07cd600843c16203f`。ScopeGate、Validate、Backend、
Frontend、Integration、Package、DeployDemo 和独立 Smoke 阶段均通过。Backend 的
`count-test-results.py` 报告 12 个 XML、93 个测试、0 failures/errors/skips；Frontend
34/34 测试通过，Vite 生产构建成功。Integration 构建成功。

发布目录为 `/opt/media-workspace/releases/0.1.0-629a71f`；manifest.commit 等于上述
完整 SHA，`sha256sum --check --quiet SHA256SUMS` 通过。DeployDemo 内和独立 Smoke
阶段各完成 8 步，均输出 `SMOKE TEST PASSED`。流水线结束后 API 与 Worker 仍在运行，
API readiness 返回 200。

| 用例 | Jenkins 验收断言 | 状态 | 当前证据 |
|---|---|---|---|
| CI-01 正常提交构建 | commit 与制品 manifest 一致、SHA 正确、测试数大于 0 | PASS | build #9 SUCCESS；12 个 XML 共 93 个后端测试，前端 34/34；Integration、Package 通过；manifest.commit 与 HEAD 一致，SHA256SUMS 校验通过 |
| CI-02 失败测试阻止发布 | 注入失败的集成测试或前端构建失败；流水线失败、归档失败报告、不部署且无新成功发布标记 | NOT_RUN | build #9 未注入失败，未验证这些失败路径断言 |
| CI-03 重启演示部署后冒烟 | 登录、上传、处理、播放及健康检查通过 | PASS | build #9 DeployDemo 和独立 Smoke 阶段均完成 8 步并通过；构建结束后 API/Worker 运行，readiness 200 |
| CI-04 新版本部署健康失败 | 保留失败证据；schema 兼容时恢复上一制品并验证 | NOT_RUN | 未执行部署健康失败与回退验证 |

以上 PASS 只覆盖表中已实际验证的断言；其他未运行用例仍按第五节记录为 NOT_RUN。

## 七、与 05 号规范的差异

- **未使用 Testcontainers。** 演示主机没有容器引擎，规范允许改用"明确隔离的 Compose 测试栈"。
  这里的做法是：每次集成运行创建独立的 `mw_it_<随机>` schema 与独立存储目录，测试结束即删除，
  绝不接触演示库。理由与实测记录见 `docs/decisions.md`。
- **集成测试的 Kafka 隔离用主题前缀实现**，而非独立 broker；消费组名同样加前缀。

## 八、运行记录

以下是 2026-09-25 的本地运行记录，与第六节 Jenkins build #9 的运行记录分开：

```
./gradlew clean check bootJar jar
  BUILD SUCCESSFUL
  test reports: 12   tests: 90  failures: 0  errors: 0  skipped: 0

bash scripts/run-integration-tests.sh :tests:e2e:integrationTest :adapter-persistence:integrationTest
  e2e 18/18 PASSED
  WorkspaceRepositoryIT 5/5 PASSED
  ProcessTranscoderIT 14/14 PASSED

bash scripts/smoke-test.sh          （每次部署后执行）
  SMOKE TEST PASSED
```

## 九、测试过程中发现并修复的缺陷

每一个都先复现、定位到首个违约边界、补回归测试，再改实现。

| 编号 | 现象 | 首个违约边界 | 责任模块 |
|---|---|---|---|
| BUG-01 | 竖屏视频被放大到 406x720（规范要求不放大） | B09 transcode → FFmpeg | adapter-transcode |
| BUG-02 | 头部完整但被截断的视频被"成功"转码成短片段而非拒绝 | B09 | adapter-transcode |
| BUG-03 | 生产端写 `media.task.requested.v1`，消费端读 `mw` 前缀名，事件被接收但无人消费 | B06 outbox → Kafka → inbox | adapter-messaging |
| BUG-04 | `@KafkaListener` 类未注册为 Bean，监听器根本没启动 | B06 | media-worker / media-api |
| BUG-05 | 登录与鉴权用 `principal.toString()` 当 user id，建空间时数据库截断报错 | B02 api → application | media-api |
| BUG-06 | 存储根目录缺失只在首次上传时暴露为 503 | B05 application → storage | adapter-storage |
| BUG-07 | 租约过期的恢复路径用要求"租约有效"的条件更新，永远匹配 0 行，任务永远停在 RUNNING | B04 application → persistence | media-application + adapter-persistence（见 CONTRACT_CHANGE-001） |
| BUG-08 | 演示主机内存不足时构建与常驻服务争抢，导致 SSH 握手超时、主机不可用 | B11 Git → Jenkins → artifact | build-delivery |

BUG-07 跨两个模块，原因与范围见 `docs/contract-changes/CONTRACT_CHANGE-001-recover-lapsed.md`。
其余每个都是单模块提交。
