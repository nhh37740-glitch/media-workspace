# media-workspace

团队影音素材管理与处理平台。Java 17 / Spring Boot 3.5 / MyBatis / MySQL 8.4 / Kafka / FFmpeg，
前端 Vue 3 + Element Plus。API 与 Worker 是两个独立 JVM 进程，共用一个 MySQL schema 和一个持久存储卷。

设计基线是 `X:\javaproject\团队影音素材平台计划书\` 下的 README 与 01—09 号文档。**它们是需求来源；
本文件只是实现约定与操作说明，不替代那套规范。**

## 模块与依赖方向

依赖只能从左指向右，不得反向，不得成环：

```
media-contracts  ←  media-domain  ←  media-application  ←  adapter-*  ←  media-api / media-worker
```

| 模块 | 唯一负责 | 禁止承担 |
|---|---|---|
| media-contracts | 公共 DTO、事件信封、错误码、状态枚举、追踪字段名 | SQL、Spring Bean、业务状态变更 |
| media-domain | 状态机、角色规则、重试与退避策略；纯 Java | IO、日志、HTTP、Kafka、数据库 |
| media-application | 用例编排、事务边界、端口定义、授权用例 | MyBatis SQL、FFmpeg 命令、Kafka 客户端 |
| adapter-persistence | MyBatis Mapper、Flyway 迁移、锁与条件更新 | 返回 HTTP 状态、调用 FFmpeg、直接发 Kafka |
| adapter-messaging | 信封序列化、outbox 投递、inbox 接入、DLQ | 执行转码、判定业务成功、改动媒体文件 |
| adapter-storage | 流式文件 IO、根目录约束、不可变发布与清理 | 决定任务终态、授予用户权限 |
| adapter-transcode | 子进程启动、管道排空、进度解析、超时与退出分类 | SQL、Kafka、HTTP |
| media-api | HTTP/SSE 映射、安全边界、finalizer 调度、启动装配 | 写 SQL、发编解码命令、重复实现 domain 规则 |
| media-worker | 领取/续期调度、执行上下文、关闭与恢复协调 | 绕过用例直接写 Mapper、暴露公网接口 |
| web | 页面、API 调用、上传分片调度、SSE 呈现 | 判定服务端权限、伪造任务状态 |
| build-delivery | 根构建、Jenkins、deploy、scripts、版本与制品 | 修改任何业务行为来让流水线变绿 |

`media-domain` 依赖 `media-contracts` 只是为了复用状态与错误码枚举（单一权威定义）。
`contracts/` 下的机器协议目录与 `media-contracts` 属同一逻辑模块。

## 修改纪律（来自 09 号规范，强制）

- 一次缺陷修复只改**一个**模块，默认最多 8 个文件（含记录）；先定位责任模块，再动手。
- 允许追加 `docs/diagnostics/` 与 `docs/handoffs/` 下的记录，不借此改全项目架构文档。
- 不顺手重构、不统一格式化、不更新无关依赖、不改根构建脚本或公共 DTO 来绕过局部错误。
- 公共契约要改时先写 CONTRACT_CHANGE 记录，再分阶段提交，每个提交仍只动一个模块。
- 修复流程：冻结现场 → 最小复现 → 沿 B01..B11 找**首个**违约边界 → 指定唯一责任模块 →
  先加能捕获错误的测试再修实现 → 逐级验证 → 提交单模块 diff 与回归结果。
- 修改范围门禁用 `scripts/check_change_scope.py`（见下）。

## 构建与测试命令

Java 工具链固定 17；Jenkins 自身用 JDK 21。所有命令都用 Wrapper，不依赖本机 Gradle。

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

./gradlew clean check bootJar      # 单元测试 + 两个可执行 JAR（不启动外部服务）
./gradlew integrationTest          # 连接真实 MySQL / Kafka / FFmpeg，每次运行独立 schema
bash scripts/run-integration-tests.sh :adapter-persistence:integrationTest
bash scripts/check_change_scope.sh --base <SHA> --head <SHA> --module <module>
```

单元测试不启动外部服务；集成测试用真实 MySQL 与 Kafka，不使用 H2 或纯 mock
（`docs/decisions.md` 记录了为何没有用 Testcontainers：演示主机没有容器引擎）。

集成测试的凭据从环境读取（`MW_DB_*`、`MW_KAFKA_BOOTSTRAP`），由
`scripts/run-integration-tests.sh` 从 `/opt/media-workspace/config/media-workspace.env`
导出到测试进程。**任何凭据都不写进仓库、日志或命令行。**

## 关键不变量

改代码前先确认不要破坏这些：

1. MySQL 是任务与素材有效状态的唯一权威；Kafka 只是异步通道；文件字节在持久卷上。
2. 事件在**写状态的那个事务里**进 outbox；Kafka 发送在事务外，ack 后按 claim_token 标记。
   确认与标记之间崩溃允许重复，靠 inbox 去重，不允许丢失。
3. 消费端先落 inbox 和业务更新，再提交 offset；坏消息先登记 poison 并写 DLQ outbox 再确认。
4. 输出文件按 `derived/{mediaId}/{generation}/{executionEpoch}/` 独立路径；
   只有持有有效租约、且 `generation/epoch/workerId/lease` 全部匹配的条件更新才允许发布指针，
   影响行数为 0 表示执行已过期，**只能丢弃结果，不能重试强写**。
5. 全局锁顺序固定：capacity_counter → workspace → upload_session → media → processing_task →
   task_attempt。不需要某表就跳过，不能反向补锁。
6. 源配额：创建上传时锁 workspace 行做「查余额 + 增加预留」；发布时 reserved 转 used；
   释放必须幂等（`quota_reserved` 布尔位），不能重复扣减，也不能低于 0。
7. 状态机是唯一判定点；终态不可被进度覆盖；每 generation 最多 3 次执行。
8. 分片与合并都先写完整不可变文件，再提交数据库引用。文件与数据库之间没有原子事务，
   所以顺序不能颠倒：宁可有孤儿文件，不可有指向半成品的行。
9. 前端隐藏按钮不是权限边界；每个修改动作都在事务内重新校验角色。
10. 密码、会话、分享原始 token、文件正文、内部存储路径**不进日志**。

## 存储键约定

全部是存储根目录的相对路径，任何绝对路径、`..` 片段或越界符号链接都会被 storage 适配器拒绝；
客户端提供的文件名只作展示，永远不拼接进路径。

```
chunk/{uploadId}/{index}-{sha256}                     已校验的分片，内容寻址
merge/{uploadId}/{expectedHash}/original.bin          合并后的原文件，内容寻址
derived/{mediaId}/{generation}/{executionEpoch}/output.mp4
derived/{mediaId}/{generation}/{executionEpoch}/poster.jpg
tmp/...                                               临时区，GC 只清这里与已终结会话的孤儿
```

合并键用「上传者声明的全文件 hash」而不是 epoch：它只在通过 hash 校验后才会被写入，
因此是内容寻址——重复执行可以直接复用，而失效的 finalizer 也写不进不匹配的内容。

## 主机与部署

- 构建/测试主机：`ubuntu@43.153.176.182`，2 vCPU / 1.9 GiB，Ubuntu 26.04。
  SSH 指纹记录在 `docs/ssh-host-fingerprint.txt`，冲突即停止。
- 部署根：`/opt/media-workspace/`（`releases/`、`current`、`var/storage`、`var/logs`、`var/run`）。
- 端口：Nginx 8088 对外；API 8080、MySQL 3306、Kafka 9092、Jenkins 8081 **只监听 127.0.0.1**。
- 内存预算与错峰策略见 `docs/runbook.md`。构建、集成测试、Jenkins 构建与转码不并行。
- 不要动这台机器上不属于本项目的服务与数据（`tat_agent`、`YDService` 等云厂商代理占了约 400 MiB）。

## 全局写作约定

解释任何内容时**不使用比喻、类比或打比方**——包括用其他语言或工具来类比当前概念。
直接陈述事实、机制、参数与返回值。适用于回复、注释、文档与计划文件。
