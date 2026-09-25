# media-workspace

## 这是什么

一个给个人和小团队集中管理影音素材的平台。使用者把拍摄的原片上传进来，平台在后台把它们
转成浏览器能直接播放的格式并生成封面，之后可以按标题检索、在线播放、用限时链接分享给
团队外的人。

要解决的具体问题有三个：

1. **原片太大，传一半断了要重来。** 平台把文件切片接收，断线后只补没传完的那几片；
   只有整份文件校验一致才会入库。
2. **原片格式浏览器放不了。** 上传完成后平台在后台自动转码为 H.264 的 MP4，
   最多 720p、保持比例、不放大，同时生成 JPEG 封面。用户不需要装任何解码器。
3. **素材散落在个人手上，权限说不清。** 空间内的成员分所有者、编辑、只读三种角色，
   每个修改动作都在服务端校验；对外分享是限时链接，可以随时撤销。

用户只点击一次“上传”。分片、重试、合并请求全部由浏览器自动完成。

## 谁在用

- **所有者**：建空间、管成员、删素材；
- **编辑**：上传、取消或重试处理任务、改标题、创建分享；
- **只读成员**：看列表、看详情、播放；
- **非成员**：看不到任何东西——不存在的空间和没有权限的空间返回同样的 404。

第一次使用需要管理员在服务器上用命令行建账号。平台不提供注册入口。

## 怎么跑起来

```bash
# 1. 准备主机（幂等）：装 JDK/Node/MySQL/Kafka/FFmpeg/Nginx，建库建主题
sudo bash scripts/provision-host.sh

# 2. 生成测试素材（全部由 FFmpeg 合成，无版权问题）
bash scripts/generate-test-media.sh /opt/media-workspace/var/test-media

# 3. 构建 + 迁移 + 部署 + 冒烟
bash scripts/deploy-demo.sh

# 4. 之后启停
bash scripts/service.sh start|stop|restart|status api|worker|all
```

构建与测试命令、端口、内存预算、排查步骤见 **[docs/runbook.md](docs/runbook.md)**。
设计选择的理由见 **[docs/decisions.md](docs/decisions.md)**。
每条验收用例的实现位置与执行状态见 **[docs/test-matrix.md](docs/test-matrix.md)**。

浏览器入口：`http://<主机>:8088`。API、MySQL、Kafka、Jenkins 都只监听回环地址。

## 系统长什么样

```
浏览器 (Vue 3 + Element Plus)
  │ HTTP / SSE
  ▼
Nginx :8088 ── 静态资源 + 反代
  │
  ▼
API JVM :8080 ──────────► MySQL :3306      任务、素材、上传、分享、outbox 的权威状态
  │  │                    (仅回环)
  │  └──────────────────► 共享存储卷        原片、分片、转码结果、封面
  │
  └── outbox 事件 ──────► Kafka :9092 ─────► Worker JVM（无业务端口）
                            (仅回环)           │ 领取任务、执行 ffprobe / FFmpeg
                                               ├──► MySQL（任务状态、执行历史）
                                               ├──► 共享存储卷（attempt 独立目录）
                                               └──► Kafka（结果事件）──► API（通知与审计投影）
```

两个 Java 进程，一个 MySQL schema，一个共享存储卷。API 与 Worker 是分开运行的进程，
不是“独立数据库的微服务”。

**7 个常驻组件**：API、Worker、MySQL、Kafka、Nginx、Jenkins Controller、Jenkins Agent。
它们不等于 7 个 OS 进程；ffprobe 与 FFmpeg 按任务临时启动。

## 数据流的两条关键约定

**MySQL 是权威，Kafka 是通道。** 任务状态只在 MySQL 里定论；事件用来驱动异步处理。
事件在写状态的那个数据库事务里进入 outbox 表，再由发布器送到 Kafka。所以 Kafka 停机
不会丢任务，只会让它晚一点开始；结果事件丢了也不会让状态回退，因为状态早就写好了。

**文件先写完整，再让数据库引用它。** 文件系统和数据库之间没有原子事务，
所以顺序不能颠倒：宁可留下没人引用的文件（由延迟 GC 清理），也不允许数据库指向半成品。
转码输出按 `derived/{mediaId}/{generation}/{executionEpoch}/` 分目录，
失效的执行只能写自己的目录，发布指针必须同时匹配代次、epoch、worker 与租约。

## 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| media-contracts | DTO、事件信封、错误码、状态枚举、追踪字段名 | 无 |
| media-domain | 状态机、角色规则、重试与退避；纯 Java | contracts |
| media-application | 用例编排、事务边界、端口定义 | domain, contracts |
| adapter-persistence | MyBatis、Flyway、锁与条件更新 | application |
| adapter-messaging | 信封序列化、outbox 投递、inbox 接入、DLQ | application |
| adapter-transcode | 子进程启动、管道排空、超时与退出分类 | application |
| adapter-storage | 流式文件 IO、根目录约束、不可变发布 | application |
| media-api | HTTP/SSE、安全边界、finalizer 调度、装配 | 全部 + 两个应用 JAR |
| media-worker | 领取/续期调度、执行上下文、恢复 | 全部 + 两个应用 JAR |
| web | 四个页面 + 分享落地页 | — |
| build-delivery | 根构建、Jenkins、部署脚本、制品 | — |

依赖方向由 `./gradlew architectureCheck` 强制。

## 交付物

`bash scripts/build-release.sh` 产出：

- `media-api-<version>.jar`、`media-worker-<version>.jar`：可执行 JAR，`java -jar` 启动；
- 7 个普通库 JAR：供编译依赖与阅读，不独立启动；
- `web/`：Vue 构建产物，由 Nginx 托管；
- `media-workspace-<version>-<commit>.zip`：上面全部加 `manifest.json` 与 `SHA256SUMS`。

`manifest.json` 记录版本、commit、构建号、构建时间、JDK/Node/FFmpeg 版本、schema 版本、
事件 schema 版本，以及每个产物的 SHA-256。全部在构建期从实际产物生成。
工作区有未提交改动时会记录 `treeState` 与改动文件列表。

## 已知限制

- **单节点 Kafka**，副本数为 1。这是演示拓扑，不宣称高可用。
- **没有容器化**。演示主机没有容器引擎，集成测试改用每次运行自建 schema 的隔离方式。
- **搜索只有 MySQL 的标题匹配**，没有全文索引，没有 Elasticsearch。
- **转码预设只有一个**（`MP4_720P_V1`）。首版不提供多码率自适应流。
- **不提供原始文件下载**：对外只有转码后的 MP4。
- 性能数字以 `docs/runbook.md` 与测试报告中的实测值为准；没有实测的地方不写数字。
