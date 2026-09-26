# 交接文档：media-workspace

日期：2026-09-26。分支 `main`，代码提交 24 个，最后一个是 `8412bbc`。
本文档的目标是：**另一个人拿着这份文档，不需要问我任何问题，就能接手继续做。**

阅读顺序建议：第 1—4 节是"怎么连上去、怎么跑起来"，第 5—11 节是"系统长什么样"，
第 12—13 节是"日常操作命令"，第 14—15 节是"已经做到哪一步了"，
第 16 节是**最重要的一节**：还没做完什么、为什么、第一步该做什么。

---

## 1. 项目是什么

团队影音素材管理与处理平台。使用者把原片上传进来，平台在后台转成浏览器能直接播放的
H.264 MP4 并生成封面，之后可以按标题检索、在线播放、用限时链接分享给团队外的人。

三个要解决的现实问题：

1. 原片大，传一半断了要重来 → 分片接收，断线只补缺片，整份文件校验一致才入库；
2. 原片格式浏览器放不了 → 上传完成后后台自动转码，用户不装解码器；
3. 素材权限说不清 → 空间内三种角色，每个修改动作服务端校验；对外分享是限时链接，可随时撤销。

用户只点一次"上传"，分片、重试、合并请求全部由浏览器自动完成。

计划书在 `X:\javaproject\团队影音素材平台计划书\`（README + 01—09 号文档 + contracts +
验收场景 + 修改范围门禁脚本）。**它是需求来源，本仓库是它的实现。**

---

## 2. 代码在哪

| 内容 | 位置 |
|---|---|
| 仓库 | `X:\javaproject\media-workspace`（Windows 本机，X: 盘） |
| 服务器副本 | `ubuntu@43.153.176.182:~/media-workspace`（含 `.git`，可作备份） |
| SSH 私钥 | `X:\javaproject\codex.pem` |
| 计划书 | `X:\javaproject\团队影音素材平台计划书\` |
| 同步脚本 | `X:\javaproject\sync-media-workspace.sh`（不在仓库里，是开发辅助） |
| 带重试的 SSH 封装 | `X:\javaproject\ssh-mw.sh`（同上） |

**X: 盘不稳定**，本次会话中消失过两次，每次约 40 秒后自行恢复。消失期间仓库与私钥都不可访问。
服务器副本可兜底，但服务器上可能缺少最近一次本机提交。

---

## 3. 远程访问方法

主机 `43.153.176.182`，用户 `ubuntu`，密钥认证。在 Windows 本机的 Bash 里执行：

```bash
# 直接连接（会经常失败，见下）
ssh -i "X:\javaproject\codex.pem" ubuntu@43.153.176.182

# 推荐：封装脚本，只重试连接失败；命令真正执行后失败会立即返回，不会重复执行
bash "X:/javaproject/ssh-mw.sh" 'uptime'
bash "X:/javaproject/ssh-mw.sh" <<'EOF'
多行脚本直接写在这里
EOF

# 同步本地源码到服务器（tar over ssh，含 .git；排除构建产物与凭据）
bash "X:/javaproject/sync-media-workspace.sh" push

# 从服务器拉回单个文件
bash "X:/javaproject/sync-media-workspace.sh" pull web/package-lock.json
```

**为什么必须用封装脚本**：这台主机的 sshd 大约**五次里只成功一次**，其余在握手中返回
`Connection closed/reset by ... port 22`，而机器本身负载很低（实测 load 0.19）。
`ssh-mw.sh` 只对这种握手失败重试。

**主机指纹**：ED25519 `SHA256:sTpylg4cljm9w2gKt6Wlw70mswZp5BOYEHuScuX5P/Y`，
同时记在仓库 `docs/ssh-host-fingerprint.txt`。**与这个值不一致就停止连接并核对**，
不要用 `StrictHostKeyChecking=no` 绕过。

**回环服务怎么访问**：API 8080、Worker 8090、MySQL 3306、Kafka 9092、Jenkins 8081
都只监听 127.0.0.1，从本机访问需要隧道：

```bash
ssh -i "X:\javaproject\codex.pem" -L 8081:127.0.0.1:8081 ubuntu@43.153.176.182
# 然后浏览器打开 http://127.0.0.1:8081
```

公网只有一个入口：`http://43.153.176.182:8088`（Nginx）。若云厂商安全组未放行 8088，
同样可用 `-L 8088:127.0.0.1:8088` 隧道访问。

**凭据在哪**：数据库密码、四个演示账号密码、Jenkins 管理员密码全在服务器的
`/opt/media-workspace/config/media-workspace.env`（0640，属主 root:ubuntu，`ubuntu` 可读）。
**仓库里没有任何凭据**；私钥只在 `X:\javaproject\codex.pem`。两者都不进版本库、不进日志。

```bash
# 在服务器上读取（不要打印到共享终端）
sudo grep '^DB_' /opt/media-workspace/config/media-workspace.env
```

---

## 4. 服务器环境（实测）

| 项目 | 实测值 |
|---|---|
| 主机 | `43.153.176.182`（腾讯云 `VM-0-6-ubuntu`） |
| 系统 | Ubuntu 26.04 LTS (resolute) |
| CPU | 2 vCPU，Intel Xeon Platinum 8255C @ 2.50GHz，每核 1 线程 |
| 内存 | 1962 MiB；交换分区 4 GiB（`/swap.img` 2 GiB + `/swapfile-mw` 2 GiB） |
| 磁盘 | `/dev/vda3` 59 GB，已用约 8 GB，可用约 48 GB |
| JDK（应用） | OpenJDK 17.0.20.1，`/usr/lib/jvm/java-17-openjdk-amd64` |
| JDK（Jenkins） | OpenJDK 21.0.12.1，`/usr/lib/jvm/java-21-openjdk-amd64` |
| Node / npm | v22.23.3 / 10.9.9 |
| FFmpeg / ffprobe | 8.0.1-3ubuntu2 |
| MySQL | 8.4.11（Ubuntu 包） |
| Kafka | 3.9.2（KRaft 单节点，`/opt/kafka_2.13-3.9.2`） |
| Nginx | 1.28.3 |
| Jenkins | 2.568.3（`/opt/jenkins/jenkins.war`） |

**云厂商代理进程 `tat_agent`（331 MiB）与 `YDService`（65 MiB）不属于本项目，不能停。**
它们常驻约 400 MiB，是内存预算必须先扣掉的部分。

安装方式全部幂等，脚本在 `scripts/provision-host.sh`（apt 装包、写 MySQL 调优、
建库授权、装 Kafka 并格式化 KRaft、建主题、建存储目录、生成密码写入环境文件、加交换分区）。
Apache 官方归档站对这台机器限速到约 13 KB/s，脚本改用华为云镜像下载 Kafka，
可用 `KAFKA_MIRROR` 覆盖。

### 目录布局

```
/opt/media-workspace/
  releases/<版本>-<短commit>/    apps/ libs/ web/ config/ scripts/ manifest.json SHA256SUMS
  current -> releases/<...>      当前生效版本
  config/media-workspace.env     唯一存放密码的地方（0640 root:ubuntu）
  var/storage/                   API 与 Worker 共用的媒体卷
  var/logs/api|worker/           console.log（人读）+ media-*.json（结构化）
  var/run/                       <服务>.pid 与 <服务>.start（进程启动时间，用于防止 PID 复用）
  var/test-media/                FFmpeg 合成的测试素材（无版权）
  var/it-storage/                集成测试的隔离存储目录
  var/kafka-logs/
```

### 端口

| 组件 | 监听 | 对外 |
|---|---|---|
| Nginx | `0.0.0.0:8088` | **是，唯一公开入口** |
| API | `127.0.0.1:8080` | 否 |
| Worker 健康端点 | `127.0.0.1:8090` | 否，只暴露 actuator |
| MySQL | `127.0.0.1:3306`（+ mysqlx 33060） | 否 |
| Kafka | `127.0.0.1:9092`（controller 9093） | 否 |
| Jenkins | `127.0.0.1:8081` | 否，需隧道 |

---

## 5. 模块地图

依赖只能从左指向右，不得反向、不得成环，由 `./gradlew architectureCheck` 强制。

```
media-contracts ← media-domain ← media-application ← adapter-* ← media-api / media-worker
                     (纯 Java)         (端口+用例)       (四类适配器)      (可执行 JAR)
```

| 模块 | 源文件 | 测试 | 唯一负责 | 禁止承担 |
|---|---|---|---|---|
| `media-contracts` | 44 | 0 | 公共 DTO、事件信封、错误码、状态枚举、追踪字段名 | SQL、Spring Bean、业务状态变更 |
| `media-domain` | 10 | 7 | 状态机、角色规则、重试与退避；纯 Java | IO、日志、HTTP、Kafka、数据库 |
| `media-application` | 67 | 0 | 用例编排、事务边界、端口定义、授权用例 | MyBatis SQL、FFmpeg 命令、Kafka 客户端 |
| `adapter-persistence` | 23 | 1 | MyBatis Mapper、Flyway 迁移、锁与条件更新 | 返回 HTTP 状态、调用 FFmpeg、直接发 Kafka |
| `adapter-messaging` | 6 | 2 | 信封序列化、outbox 投递、inbox 接入、DLQ | 执行转码、判定业务成功、改媒体文件 |
| `adapter-transcode` | 4 | 1 | 子进程启动、管道排空、进度解析、超时与退出分类 | SQL、Kafka、HTTP |
| `adapter-storage` | 1 | 1 | 流式文件 IO、根目录约束、不可变发布与清理 | 决定任务终态、授予用户权限 |
| `media-api` | 24 | 1 | HTTP/SSE 映射、安全边界、finalizer 调度、启动装配 | 写 SQL、发编解码命令、重复实现 domain 规则 |
| `media-worker` | 9 | 1 | 领取/续期调度、执行上下文、关闭与恢复协调 | 绕过用例直接写 Mapper、暴露公网接口 |
| `web` | 8 个 `.vue` + 12 个 `.js` | 4 个 spec | 页面、API 调用、上传分片调度、SSE 呈现 | 判定服务端权限、伪造任务状态 |
| `tests/e2e` | 0 | 5 | 跨模块的故障注入测试 | — |
| `build-delivery` | 15 个脚本 + 10 个部署文件 | — | 根构建、Jenkins、部署、制品 | 修改业务行为让流水线变绿 |

**模块所有权与修改纪律见仓库根目录 `CLAUDE.md` 与计划书 09 号规范。** 核心一条：
一次缺陷修复只改一个模块，最多 8 个文件；先定位责任模块再动手；不顺手重构。
公共契约要改时先写 `docs/contract-changes/` 记录。

---

## 6. 架构

### 进程与数据流

```
浏览器 (Vue 3 + Element Plus)
  │ HTTP / SSE
  ▼
Nginx :8088 ── 静态资源 + 反代（SSE 关闭缓冲）
  │
  ▼
API JVM :8080 ──────► MySQL :3306        任务、素材、上传、分享、outbox 的权威状态
  │   │                                    (仅回环)
  │   └──────────────► 共享存储卷          原片、分片、转码结果、封面
  │
  └── outbox 事件 ───► Kafka :9092 ─────► Worker JVM（无业务端口，健康端点 :8090）
                         (仅回环)           │  领取任务、跑 ffprobe / FFmpeg
                                            ├──► MySQL（任务状态、执行历史）
                                            ├──► 共享存储卷（attempt 独立目录）
                                            └──► Kafka（结果事件）──► API（通知与审计投影）
```

7 个常驻组件：API、Worker、MySQL、Kafka、Nginx、Jenkins Controller、Jenkins Agent。
它们不等于 7 个 OS 进程；ffprobe 与 FFmpeg 按任务临时启动。

### 十条关键不变量（改代码前必须确认不破坏）

1. **MySQL 是权威**，Kafka 只是异步通道，文件字节在持久卷上。
2. 事件在**写状态的那个事务里**进 outbox；Kafka 发送在事务外，ack 后按 `claim_token` 标记。
   确认与标记之间崩溃允许重复（靠 inbox 去重），不允许丢失。
3. 消费端先落 inbox 和业务更新，**再**提交 offset；坏消息先登记 poison 并写 DLQ outbox 再确认。
4. 输出文件按 `derived/{mediaId}/{generation}/{executionEpoch}/` 独立路径；
   只有持有有效租约、且 `generation/epoch/workerId/lease` 全部匹配的条件更新才能发布指针，
   **影响行数为 0 表示执行已过期，只能丢弃结果，不能重试强写**。
5. **全局锁顺序固定**：`capacity_counter → workspace → upload_session → media → processing_task →
   task_attempt`。不需要某表就跳过，不能反向补锁。
6. 源配额：创建上传时锁 workspace 行做"查余额 + 增加预留"；发布时 reserved 转 used；
   释放必须幂等（`quota_reserved` 布尔位），不能重复扣减、不能低于 0。
7. **状态机是唯一判定点**；终态不可被进度覆盖；每 generation 最多 3 次执行。
8. 分片与合并都**先写完整不可变文件，再提交数据库引用**。文件与数据库之间没有原子事务，
   顺序不能颠倒：宁可有孤儿文件（延迟 GC 清），不可有指向半成品的行。
9. 前端隐藏按钮**不是**权限边界；每个修改动作都在事务内重新校验角色。
10. 密码、会话、分享原始 token、文件正文、内部存储路径**不进日志**。

### 事务边界放在哪（容易踩的坑）

- 事务在**应用层**，不在适配器里。适配器不带 `@Transactional`。
- **需要先做文件 IO 的步骤必须拆到独立 Bean**。`@Transactional` 加在同一个类的私有/包内方法上
  不会经过代理，事务根本不生效，而且**不会报错**，只在崩溃时表现为数据不一致。
  已有三处这样拆：`UploadService`/`UploadChunkCommitService`、
  `UploadFinalizeService`/`UploadFinalizeTransactionService`、
  `TaskExecutionService`/`TaskPublicationService`。
- **租约与截止时间一律用数据库时钟**（SQL 里 `UTC_TIMESTAMP(6)`），端口只接收 `Duration`，
  不接收调用方算好的 `Instant`。

---

## 7. 数据库

MySQL 8.4，InnoDB，utf8mb4，`DATETIME(6)` 全部 UTC，连接固定 `READ-COMMITTED`。
UUID 用 `CHAR(36)`，hash 用 `CHAR(64)`，状态用 `VARCHAR` + `CHECK`。
schema 由 **Flyway** 编号迁移管理，禁用应用自动建表。

迁移文件：`adapter-persistence/src/main/resources/db/migration/`
- `V1__baseline.sql` —— 全部业务表
- `V2__spring_session.sql` —— 框架的会话表

当前 schema 版本 **2**。

19 张表：

| 表 | 作用 | 关键约束 |
|---|---|---|
| `app_user` | 账号 | `username` 唯一 |
| `workspace` | 空间 | `CHECK used+reserved<=quota`，三者非负 |
| `workspace_member` | 成员 | `(workspace_id,user_id)` 复合主键，`role CHECK`，OWNER 行不可被改/删 |
| `upload_session` | 上传会话 | `state CHECK`，`quota_reserved` 布尔位，`finalize_epoch` + `lease_until` |
| `upload_chunk` | 分片 | `(upload_id,chunk_index)` 主键，`storage_key` 唯一，块不可覆盖 |
| `media` | 素材 | `source_key` 唯一，`status CHECK`，`deleted_at` 逻辑删除 |
| `processing_task` | 任务 | `media_id` 唯一，`state CHECK`，`attempt<=3`，`progress 0..100` |
| `task_attempt` | 执行历史 | `(task_id,generation,attempt)` 唯一，`state CHECK` 含 `LOST` |
| `outbox_event` | 待发事件 | `state CHECK`，`claim_token`/`claim_until` 租约 |
| `inbox_event` | 消费去重 | `(consumer_group,event_id)` 主键，存 `body_hash` |
| `poison_message` | 坏消息 | `(topic,partition_id,offset_id)` 主键，只存坐标与 hash |
| `share_link` | 分享 | `token_hash` 唯一（**只存散列，不存原始 token**） |
| `share_session` | 分享会话 | 兑换后的服务端会话 |
| `audit_event` | 审计投影 | `source_event_id` 唯一（消费重复不会写两行） |
| `idempotency_record` | 幂等响应 | `(user,route,resource,key_hash)` 主键，空资源用哨兵 `-` |
| `capacity_counter` | 全局未终结任务计数 | 单行，状态变更与计数同事务 |
| `SPRING_SESSION` / `..._ATTRIBUTES` | 会话存储 | 框架自带 schema |
| `flyway_schema_history` | 迁移记录 | — |

重点索引（按 04 号规范）：`processing_task(state,next_run_at,id)`、
`processing_task(state,lease_until)`、`outbox_event(state,next_run_at,event_id)`、
`media(workspace_id,deleted_at,created_at,id)`、`upload_session(state,expires_at)`。

---

## 8. 存储

全部是**存储根目录的相对路径**。任何绝对路径、`..` 片段、反斜杠或越界符号链接都会被
storage 适配器拒绝；客户端提供的文件名只作展示，永远不拼接进路径。

```
chunk/{uploadId}/{index}-{sha256}                     已校验的分片，内容寻址
merge/{uploadId}/{expectedHash}/original.bin          合并后的原文件，内容寻址
derived/{mediaId}/{generation}/{executionEpoch}/output.mp4
derived/{mediaId}/{generation}/{executionEpoch}/poster.jpg
tmp/...                                               临时区，GC 只清这里与已终结会话的孤儿
```

**合并键用"上传者声明的全文件 hash"而不是 epoch**：它只在通过 hash 校验后才会被写入，
因此是内容寻址——重复执行可直接复用，失效的 finalizer 写不进不匹配的内容。
epoch 仍然守着发布那一步。

发布采用**先写临时文件再原子 rename**，读方看不到半成品；已存在的键不会被异内容覆盖。
存储适配器**在启动时**创建根目录并写一个探针文件验证可写，失败即启动失败——
早期版本只在首次上传时才发现，表现为 503，看不出是卷不存在。

---

## 9. Kafka 与事件

| 主题 | 分区 | 消费者组 |
|---|---|---|
| `media.task.requested.v1` | 3 | `media-worker-v1`（Worker） |
| `media.task.result.v1` | 3 | `media-api-notify-v1`（API） |
| `media.events.dlq.v1` | 1 | 无人消费，供运维 |

副本数 1（演示拓扑，不宣称高可用）。保留期 7 天。**主题显式创建，不用自动创建。**

**演示环境的 `KAFKA_TOPIC_PREFIX` 为空**，主题名与规范一致。前缀只用于并行集成测试的隔离，
而且**只在发送那一刻解析一次**——生产端与消费端曾经各按自己的方式解析，导致事件被接收但无人消费。

事件信封（`contracts/event-envelope.schema.json`）必填字段：
`eventId, eventType, schemaVersion=1, occurredAt, mediaId, taskId, generation, aggregateVersion,
requestId, traceId, producerInvocationId, payload`，`causationEventId` 可空。
payload 按 `eventType` 分四种，都不携带绝对路径、存储键或媒体字节。

**生产端**：幂等发送 + `acks=all`，发送结果被 await。**消费端**：`enable.auto.commit=false`，
手动确认，且只在业务事务提交后确认；容器保持分区内顺序，不允许大 offset 越过未持久化的记录。
坏消息在一个事务里登记 poison 并写 DLQ outbox，**之后**才确认原 offset。

---

## 10. HTTP 接口

完整契约：`contracts/openapi.yaml`（OpenAPI 3.0.3，全部路径与 schema）。
语义基线是计划书 03 号文档。前缀 `/api/v1`。

| 分组 | 路径 |
|---|---|
| 认证 | `GET /auth/csrf`、`POST /auth/login`、`POST /auth/logout`、`GET /auth/me` |
| 空间与成员 | `POST|GET /spaces`、`PUT|DELETE /spaces/{id}/members/{userId}`、`GET /spaces/{id}/members` |
| 上传 | `POST /spaces/{id}/uploads`（需 Idempotency-Key）、`GET|DELETE /uploads/{id}`、`PUT /uploads/{id}/chunks/{index}`、`POST /uploads/{id}/complete` |
| 素材 | `GET /spaces/{id}/media`、`GET|PATCH|DELETE /media/{id}`、`GET /media/{id}/content`（+HEAD）、`GET /media/{id}/poster` |
| 任务 | `GET /tasks/{id}`、`GET /tasks/{id}/attempts`、`POST /tasks/{id}/cancel`、`POST /tasks/{id}/retry`（需 Idempotency-Key）、`GET /tasks/{id}/events`（SSE） |
| 分享 | `POST|GET /media/{id}/shares`、`DELETE /shares/{shareId}` |
| 公开 | `POST /public/share-access`、`GET|HEAD /public/share-access/content` |

约定：未知 JSON 字段**拒绝**；字符串去首尾空白后校验；分页 `page` 从 1、`pageSize` 1..100
默认 20、顺序固定 `createdAt DESC, id DESC`；错误体统一 `{code,message,requestId,resourceId,details}`；
`401` 未登录、`403` 已知成员无权限、`404` 不存在**或不可见**（两者不区分）、`409` 状态冲突、
`413` 超限、`422` 输入无效、`429` 配额或容量、`503` 依赖不可用。

**CSRF**：`GET /auth/csrf` 取 token，所有修改类请求带 `X-CSRF-TOKEN`。分享兑换也要带——
token 与取得它的会话绑定，用别的会话的 token 会被拒（冒烟脚本专门断言了这一点）。

**Range**：只支持单范围 `bytes=start-end` / `start-` / `-suffix`；合法 206 + `Content-Range`，
越界 416 + `bytes */total`，多范围按产品约定 416，语法错误 400。**授权先于范围计算**，
且文件从不整份读入内存。

**SSE**：连接先发 `snapshot`，状态变化发 `state`，15 秒心跳；事件 `id` 是任务递增 `version`，
客户端丢弃更小的；队列有界，读得太慢的连接被关闭；每次轮询重新校验成员资格，失权即关闭。
**SSE 只是提示，终态以 `GET /tasks/{id}` 为准。**

---

## 11. 前端

Vue 3 + Element Plus + vue-router，源码在 `web/`：

| 文件 | 作用 |
|---|---|
| `src/views/LoginView.vue` | 登录 |
| `src/views/MediaLibraryView.vue` | 素材库：空间切换、标题搜索、上传队列、封面、删除、播放 |
| `src/views/TasksView.vue` | 处理任务：中文状态、进度、失败码、取消/重试，5 秒轮询 |
| `src/views/TaskDetailView.vue` | 任务详情：代次、执行历史、错误摘要、SSE 事件流 |
| `src/views/WorkspaceView.vue` | 共享空间：成员角色管理、限时分享创建与撤销 |
| `src/views/ShareView.vue` | 分享落地页（公开） |
| `src/components/UploadQueue.vue` | 一次点击完成分片上传的队列组件 |
| `src/api/client.js` | fetch 封装：cookie、CSRF、错误码映射、幂等键 |
| `src/api/uploader.js` | 分片调度：算 hash、切片、重传、请求合并 |
| `src/api/eventStream.js` | **基于 fetch 流**的 SSE 解析（不是 EventSource） |
| `src/utils/taskStates.js` | 状态中文化（未知状态原样显示，不隐藏） |
| `src/utils/format.js` | 字节、时长、UTC→本地时间格式化 |

4 个单测文件（`*.spec.js`）：状态文案、格式化、HTTP 客户端（CSRF 与错误映射）、SSE 帧解析
（含跨读取边界拆分的帧、心跳注释、被拒时保留真实状态码）。

分享链接用 `#/share#token=...`，token 只走 URL fragment，**不经过服务器**（不进访问日志），
兑换后立即用 `history.replaceState` 从地址栏清除。

**当前状态：从未构建成功。见第 16.1 节。**

---

## 12. 日常命令

### 构建与测试

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd ~/media-workspace

./gradlew clean check bootJar jar        # 单元测试 + 两个可执行 JAR + 库 JAR，不启外部服务
./gradlew architectureCheck versionLockCheck   # 依赖方向与版本锁定
python3 scripts/validate-contracts.py    # 契约校验（schema/样例/拒绝用例/feature 引用）
python3 scripts/count-test-results.py --require-nonzero   # 测试数非 0，否则失败

# 集成测试（真实 MySQL/Kafka/FFmpeg，每次自建独立 schema）
bash scripts/run-integration-tests.sh
bash scripts/run-integration-tests.sh :tests:e2e:integrationTest
bash scripts/run-integration-tests.sh :adapter-persistence:integrationTest

# 转码测试需要测试素材
bash scripts/generate-test-media.sh /opt/media-workspace/var/test-media

# 单模块修改范围门禁
bash scripts/check_change_scope.sh --base <SHA> --head <SHA> --module <模块名>
```

### 部署

```bash
bash scripts/deploy-demo.sh
```

流程：**先停服务**（腾内存，见第 17 节）→ 构建发布目录 → 校验 `SHA256SUMS` →
迁移 schema（独立进程，不经过两个服务）→ 切换 `current` 符号链接 → 启动 → 等就绪 →
冒烟；冒烟失败把符号链接切回上一版，**数据库不回滚**（迁移按向前兼容新增字段设计）。

### 启停与状态

```bash
bash scripts/service.sh start|stop|restart|status api|worker|all [发布目录]
```

只操作自己记录的 PID，停止前核对 `/proc/<pid>/stat` 的启动时间与记录值一致才动手，
**不按进程名杀进程**。这台机器上还有别的东西在跑。

### 冒烟

```bash
bash scripts/smoke-test.sh          # 走 Nginx，跑完整业务链路，失败即退出非 0
```

### CI 前置

```bash
bash scripts/ci-prepare.sh          # 停自有服务、检查 MySQL/Kafka、生成测试素材、报可用内存
```

### 运维查询

```bash
set -a; . /opt/media-workspace/config/media-workspace.env; set +a
# 任务与事件状态
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -e "
  SELECT state,COUNT(*) FROM processing_task GROUP BY state;
  SELECT topic,state,publish_attempt FROM outbox_event ORDER BY created_at DESC LIMIT 5;"
# 按任务串日志
grep '"taskId":"<任务号>"' /opt/media-workspace/var/logs/worker/media-worker.json | head
# 健康
curl -s http://127.0.0.1:8080/actuator/health/readiness
curl -s http://127.0.0.1:8090/actuator/health
```

---

## 13. 配置项

全部通过环境变量注入，默认值写在 `media-api/src/main/resources/application.yml` 与
`media-worker/src/main/resources/application.yml`。

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | 环境文件 | 数据库连接，**无默认密码** |
| `KAFKA_BOOTSTRAP` | `127.0.0.1:9092` | broker 地址 |
| `KAFKA_TOPIC_PREFIX` | 空 | 演示环境留空；并行测试用它隔离 |
| `STORAGE_ROOT` | 环境文件 | 共享媒体卷根目录 |
| `HTTP_PORT` | 8080 | API 端口 |
| `HTTP_ADDRESS` | `127.0.0.1` | **API 绑定地址，默认回环** |
| `WORKER_HEALTH_PORT` | 8090 | Worker actuator 端口 |
| `CHUNK_SIZE_BYTES` | 8388608 | 分片大小，由服务端决定并发给客户端 |
| `MAX_UPLOAD_SIZE_BYTES` | 1073741824 | 单文件上限 |
| `WORKSPACE_QUOTA_BYTES` | 10737418240 | 单空间源文件配额 |
| `MAX_OPEN_UPLOADS_PER_USER` | 3 | 单人未结束上传数 |
| `MAX_UNFINISHED_TASKS` | 100 | 全局未终结任务上限 |
| `WORKER_CONCURRENCY` | 2 | 每 Worker 执行槽位 |
| `OPEN_UPLOAD_TTL_HOURS` | 24 | OPEN 会话存活 |
| `GC_GRACE_PERIOD_HOURS` | 24 | 延迟 GC 宽限期 |
| `MAX_ACTIVE_SHARES_PER_MEDIA` | 20 | 单素材有效分享上限 |
| `TASK_DEADLINE_MINUTES` | 30 | 单次执行墙钟上限 |
| `FFMPEG_PATH` / `FFPROBE_PATH` | `ffmpeg` / `ffprobe` | 二进制路径 |
| `MW_LOG_LEVEL` | INFO | 业务日志级别 |

---

## 14. 测试清单与实测结果

**全部为实际运行结果**，命令与退出码见 `docs/test-matrix.md` 第八节。

| 套件 | 数量 | 结果 |
|---|---|---|
| `./gradlew check`（单元） | **90** | 0 失败、0 错误、0 跳过 |
| `:tests:e2e:integrationTest` | **18** | 全过 |
| `:adapter-persistence:integrationTest` | 5 | 全过 |
| `:adapter-transcode:integrationTest` | 14 | 全过（真实 FFmpeg） |
| `:adapter-storage:test` | 20 | 全过 |
| `scripts/smoke-test.sh`（端到端） | 13 步 | `SMOKE TEST PASSED` |

覆盖到的验收用例（P0 为主）：JOB-01~05、07~09、MSG-01、MSG-05、PROC-01~07、UP-02、UP-04、
UP-07、UP-08（同体重放）、UP-11、ACL-01/02/04、AUTH-01/02、AUTH-03（部分）、SHARE-01/02、
PLAY-01/02、DATA-01、LOAD-01、MOD-01。

**完整列表与仍未运行的项在 `docs/test-matrix.md`**，那里逐条标注了 PASS / NOT_RUN 与原因。

端到端冒烟实测链路：

```
登录 → 创建空间 → 分片上传真实 mp4 → 同幂等键重放返回同一会话
→ complete → 合并 → 任务 RUNNING 3% → SUCCEEDED 100%
→ 播放 211554 字节 → Range 请求 206 → 封面 200
→ 创建分享 → 越会话 CSRF 被拒 → 分享播放 211554 字节 → 撤销后新请求 404
→ 登出后会话失效
SMOKE TEST PASSED
```

---

## 15. 测试期间发现并修复的 8 个真实缺陷

每条都先复现、定位到首个违约边界、补回归测试，再改实现。
完整的边界链分析在 `docs/test-matrix.md` 第九节。

| 编号 | 现象 | 首个违约边界 | 责任模块 |
|---|---|---|---|
| BUG-01 | 竖屏视频 360×640 被放大到 406×720，规范要求不放大 | B09 transcode → FFmpeg | adapter-transcode |
| BUG-02 | 头部完整但被截断的视频被"成功"转码成短片段而非拒绝（FFmpeg 退出码 0） | B09 | adapter-transcode |
| BUG-03 | 生产端写 `media.task.requested.v1`，消费端读 `mw` 前缀名，事件被接收但无人消费，任务永远 WAITING_EVENT | B06 outbox → Kafka → inbox | adapter-messaging |
| BUG-04 | `@KafkaListener` 类未注册为 Bean，监听器根本没启动（同样的静默症状） | B06 | media-worker / media-api |
| BUG-05 | 登录与鉴权用 `principal.toString()` 当 user id，建空间时数据库截断报错 | B02 api → application | media-api |
| BUG-06 | 存储根目录缺失只在首次上传时暴露为 503，看不出是卷不存在 | B05 application → storage | adapter-storage |
| BUG-07 | 租约过期的恢复路径用要求"租约有效"的条件更新，永远匹配 0 行，任务永远停在 RUNNING | B04 application → persistence | media-application + adapter-persistence（见 `CONTRACT_CHANGE-001`） |
| BUG-08 | 构建与常驻服务争抢内存，主机抖到 SSH 握手超时约一小时不可用 | B11 Git → Jenkins → artifact | build-delivery |

另有 **3 个测试自身的缺陷**也一并修了，因为它们让测试说谎：
e2e 支持类为每个 mapper 开一个从不关闭的会话（池耗尽，18 个用例全挂在连接超时）；
测试间共享库导致 `claim` 取到别的用例的任务（有的用例因此**空过**）；
冒烟脚本复用了另一个会话的 CSRF token（产品拒绝得对，是测试写错了）。

**BUG-07 是唯一跨两个模块的提交**，原因与范围在
`docs/contract-changes/CONTRACT_CHANGE-001-recover-lapsed.md` 里写明了：接口与其唯一实现
不能拆成两个能编译的提交。

---

## 16. 未完成的事

### 16.1 前端从未构建成功，Nginx 返回 403 ← 优先做这个

`web/` 下源码完整（8 个 `.vue` + 12 个 `.js` + 4 个 spec），但：

- **`web/package-lock.json` 不存在**，所以 `scripts/build-release.sh` 直接跳过了前端构建，
  发布目录里的 `web/` 是**空的**；
- 访问 `http://43.153.176.182:8088/` 得到 Nginx 403；
- 尝试生成 lockfile 时 `npm install` 报
  `npm error Cannot read properties of null (reading 'edgesOut')` 后退出，**原因未定位**。

后端与前端之间的接口是通的（冒烟脚本走的就是 HTTP API），缺的只是前端产物本身。

**第一步**：

```bash
bash "X:/javaproject/ssh-mw.sh" 'cd ~/media-workspace && bash scripts/ci-prepare.sh && cd web && rm -rf node_modules && npm install'
```

若仍失败：把 npm 升到 11 或 12（当前 10.9.9），或删掉 `package.json` 里的 `engines` 字段再试。
成功后：

```bash
cd ~/media-workspace/web && npm test -- --run && npm run build
bash /x/javaproject/sync-media-workspace.sh pull web/package-lock.json    # 拉回本机并提交
bash "X:/javaproject/ssh-mw.sh" 'cd ~/media-workspace && bash scripts/deploy-demo.sh'
```

### 16.2 Jenkins 没有跑过任何一次流水线

已安装 Jenkins 2.568.3（控制器回环 8081、0 执行器、Agent 节点 `media-workspace-agent` 已定义、
systemd 单元与 `deploy/jenkins/init.groovy.d/` 初始化脚本都已提交），但：

- 管理员凭据认证始终返回 **401**；
- 曾 `rm -rf /opt/jenkins/users /opt/jenkins/config.xml` 后重启，账号与 realm 仍被重现，
  **根因未确定**；这个反常现象本身值得先查清（怀疑 Jenkins 在启动早期就写回了配置）；
- 插件因此无法通过 CLI 安装，`workflow-aggregator` 缺失，流水线跑不起来。

子代理失败前定位到两点，供后续排查：
1. CLI 的 `/cli?remoting=false` 用 **HTTP Basic**，凭据被拒返回 401，权限不足才是 403，
   所以 401 是"密码不匹配"而不是权限问题；
2. 它自己的脚本把凭据文件写成了 curl 配置格式（`user = "admin:..."`），
   CLI 会把整行当作密码——**先确认凭据传递格式，别在这上面浪费时间**。

**结论：CI-01 ~ CI-04、MOD-02/MOD-03 的 Jenkins 执行形式全部 NOT_RUN。**
`Jenkinsfile` 里的命令与本地完全一致，本地已全部跑通，**但这不能替代由 Jenkins 驱动的执行**，
两者不可互相代替。

### 16.3 其他 NOT_RUN 的验收用例

完整清单在 `docs/test-matrix.md` 第五节。摘要：

| 用例 | 缺什么 |
|---|---|
| MSG-02 / 03 / 04 / 06 | outbox 确认后崩溃重发、消费提交后确认前退出、消费回滚不提交 offset、Kafka 停机恢复。代码都实现了（inbox 去重、poison 后再确认、DLQ outbox），缺的是真实 Kafka 的重放与进程终止编排 |
| JOB-06 | 同重试键并发重试只 +1 代。幂等已实现，缺并发测试 |
| PLAY-03 | 浏览器拖动播放，需浏览器自动化 |
| SSE-01 / 02 | 断线重连、慢客户端。SSE 已实现（快照、版本去重、有界队列、每次轮询重查权限、15 秒心跳），缺自动化测试 |
| LOG-01 ~ 03 | 结构化日志字段已按规范输出，缺把 B01..B09 串起来的断言测试 |
| DATA-02 | 万条数据的 EXPLAIN 对比。索引已按 04 号规范建立 |
| DATA-03 | GC 与有效租约并发的删除保护。GC 已实现并在删除前复核引用 |
| DATA-04 | 备份恢复演练 |
| LOAD-02 | 双 Worker 与单 Worker 吞吐对比 |
| ACL-03 | viewer 已开 SSE 后被移出成员，实现上每次轮询都重查，缺自动化测试 |

---

## 17. 环境陷阱（踩过的坑，别再踩）

1. **内存是硬约束，构建与常驻服务不能并行。**
   曾观测到 `./gradlew clean check bootJar jar` 与两个应用 JVM 同时运行时，机器在交换分区上
   抖到 **SSH 握手超时、主机不可用约一小时**，恢复后 load 只有 0.19（说明不是负载高，
   是内存不够）。规则：**构建 / 集成测试 / Jenkins 之前先跑 `scripts/ci-prepare.sh`**，
   它只停自有服务，MySQL 与 Kafka 保留给集成测试用。
   实测常驻占用：MySQL ~120 MiB、Kafka ~137 MiB、API ~280 MiB、Worker ~247 MiB、
   Jenkins ~243 MiB、Nginx ~15 MiB；再加云厂商代理 ~400 MiB。
2. **SSH 约五次成功一次**，用 `ssh-mw.sh`，别以为是网络断了。
   如果 SSH 完全不通但端口 22 开着、ICMP 通，多半就是上面第 1 条。
3. **X: 盘会消失**（本次两次，各约 40 秒自行恢复），消失期间仓库与私钥都不可读。
   重要改动尽快 commit + `sync-media-workspace.sh push`。
4. **不要按进程名杀进程**。这台机器上有云厂商代理、MySQL、Kafka 在跑。
   `scripts/service.sh` 只操作自己记录的 PID 并核对启动时间。
5. **Windows 上写 shell 脚本注意换行**：带 `\r` 的 shebang 会报
   `env: 'bash\r': No such file or directory`，看起来像解释器缺失。仓库有
   `scripts/normalize-line-endings.sh`，提交前跑一下；`.gitattributes` 已配置 `*.sh text eol=lf`。
6. **同步不等于检出**：`sync-media-workspace.sh` 是解包覆盖，本地删掉的文件会在服务器上残留
   并被编译。脚本里已加 `git clean -fd` 处理，但改脚本时别把这一步去掉。
7. **Apache 归档站对该主机限速约 13 KB/s**，下载 Kafka 用 `KAFKA_MIRROR`
   （默认华为云镜像）。Jenkins 更新站点默认地址也慢，单元里配了清华镜像。
8. **改 MySQL 配置后要重启**才生效，`provision-host.sh` 会重启；
   重复执行它**不会**重写已有环境文件里的密码（否则会把自己锁在库外）。

---

## 18. 排查手册（按症状）

| 症状 | 先看什么 |
|---|---|
| 任务停在 `WAITING_EVENT` | 请求事件没被消费。查 outbox 是否已发、主题名两端是否一致、Worker 是否在跑、`KAFKA_TOPIC_PREFIX` 是否为空 |
| 任务停在 `RUNNING` 不动 | Worker 续期没跑或 Worker 已死。15 秒内 `TaskRecoveryService` 应把过期租约记为 LOST 并重排或置 FAILED |
| 上传返回 503 | 存储卷不可用。适配器启动时就会校验根目录可写，启动后再出现 503 说明磁盘满或卷被卸载：`df -h /opt/media-workspace` |
| 上传返回 429 | 空间配额或未结束上传数超限；也可能是全局任务计数满（看 `capacity_counter`） |
| 素材一直不可播放 | 任务是否 SUCCEEDED、`media.status` 是否 READY、`output_key` 是否有值 |
| 播放 416 | Range 越界或多范围（多范围按产品约定返回 416） |
| 分享 404 | token 不存在/已撤销/已过期/素材已删——四种情况故意返回同样结果 |
| 登录 401 | 区分"未登录"与"凭据错误"：看错误码 `AUTH_REQUIRED` vs `INVALID_CREDENTIALS` |
| 修改类请求 403 `CSRF_INVALID` | 没带 `X-CSRF-TOKEN`，或 token 来自别的会话 |
| 机器完全失去响应 | 第 17 节第 1 条：构建与常驻服务同时在跑。等构建结束 |

---

## 19. 安全姿态

**公开面**：只有 Nginx `:8088`。API、Worker、MySQL、Kafka、Jenkins 全部只监听 `127.0.0.1`。

**已做**：
- 会话存 MySQL（重启不掉线），登录成功更换会话 ID；
- CSRF 用框架机制，所有修改类请求含登录/登出/分享兑换都校验，**没有手写过滤器绕过**；
- 密码 BCrypt（强度 10）；登录失败不区分"用户不存在"与"密码错误"，防账号枚举；
- 分享只存 token 的 SHA-256，原始 token 只在创建响应里返回一次；token 走 URL fragment 不进日志；
- 分享兑换出的 cookie 是 HttpOnly、SameSite=Lax、作用域限定 `/api/v1/public`；
- 存储适配器在每次操作时校验路径在根目录内，含符号链接解析后复核；
- 日志不记录密码、会话、分享 token、文件正文、内部存储路径；
- 普通 API 错误不输出堆栈；未知异常只回 `INTERNAL_ERROR`，堆栈只在服务端记录一次。

**尚未做**（如果这个项目要真正对外，这些是缺口）：
- 无 HTTPS。Nginx 只有 80/8088 明文；cookie 的 `Secure` 属性因此未开启。
- 无限流、无登录失败锁定、无验证码。
- 无审计日志查询接口（`audit_event` 表在写，但没有读取端点）。
- Jenkins 管理员凭据问题未解决，因此它的安全配置未经验证。
- 演示账号密码在服务器环境文件里以明文存在（这是设计选择：不写进仓库，但也没有走密钥管理服务）。

---

## 20. 关键文件索引

| 我想改… | 看这个 |
|---|---|
| 状态机、角色规则、重试退避 | `media-domain/src/main/java/com/mediaworkspace/domain/` |
| 事务边界、用例编排、端口定义 | `media-application/.../service/`、`.../port/` |
| SQL、锁、条件更新 | `adapter-persistence/.../mapper/*.xml` 与 `.../repository/` |
| 表结构 | `adapter-persistence/src/main/resources/db/migration/V1__baseline.sql` |
| FFmpeg 命令行与进程控制 | `adapter-transcode/.../FfmpegArguments.java`、`ProcessRunner.java` |
| 存储键与路径约束 | `adapter-storage/.../LocalMediaStorage.java` |
| Kafka 生产/消费/信封 | `adapter-messaging/.../` |
| HTTP 接口与安全 | `media-api/.../web/`、`.../config/SecurityConfiguration.java` |
| 领取/续期调度 | `media-worker/.../schedule/` |
| 接口契约 | `contracts/openapi.yaml`、`contracts/event-envelope.schema.json` |
| 运维步骤 | `docs/runbook.md` |
| 设计决策与理由 | `docs/decisions.md`（10 条） |
| 用例状态 | `docs/test-matrix.md` |
| 契约变更 | `docs/contract-changes/` |
| 模块纪律 | `CLAUDE.md`、计划书 09 号规范 |

---

## 21. 一句话总结

后端链路是**完整、真实可跑、测试实测为绿**的，8 个缺陷是真实定位并修复的（含 1 个跨模块
契约变更，已按规定记录）；**前端产物与 Jenkins 流水线这两块没有交付**，
`web/package-lock.json` 缺失导致前端从未构建，Jenkins 管理员凭据认证失败导致流水线从未执行。
两块的第一个排查动作都写在第 16 节，没有含糊。
