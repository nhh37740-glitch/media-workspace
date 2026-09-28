# 运行手册

面向在这台演示主机上部署、启动、排查的人。命令都假设当前目录是仓库根目录，
`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`。

## 1. 主机与端口

主机：`ubuntu@43.153.176.182`，2 vCPU / 1962 MiB 内存 / 59 GB 磁盘，Ubuntu 26.04。
SSH 主机指纹记录在 `docs/ssh-host-fingerprint.txt`，如与本机记录冲突应停止连接并核对。

| 组件 | 监听 | 对外 |
|---|---|---|
| Nginx | 0.0.0.0:8088 | 是，唯一公开端口 |
| API Docker 容器 | host 网络，127.0.0.1:8080 | 否；Compose 不发布端口 |
| Worker Docker 容器 | host 网络，127.0.0.1:8090 健康端点 | 否；只提供 actuator |
| MySQL | 127.0.0.1:3306 | 否 |
| Kafka | 127.0.0.1:9092 | 否 |
| Jenkins | 127.0.0.1:8081 | 否，需要时用 SSH 隧道 |

从本机访问 Jenkins：`ssh -L 8081:127.0.0.1:8081 -i codex.pem ubuntu@43.153.176.182`，
然后浏览器打开 `http://127.0.0.1:8081`。

## 2. 实测内存预算

这台机器上还有云厂商的代理进程（`tat_agent`、`YDService` 等）常驻约 400 MiB，**不属于本项目，
不能停**。因此可用内存约 1.5 GiB。

2026-09-25 实测的常驻占用（RSS，`ps -eo rss`；当时 API/Worker 以宿主机 JVM 运行）：

| 组件 | 实测 | 配置 |
|---|---|---|
| mysqld | 约 280 MiB | `innodb_buffer_pool_size=96M`，`performance_schema=OFF` |
| Kafka (KRaft) | 约 200–320 MiB | `-Xmx256m` |
| API | 约 240 MiB | `-Xmx192m -XX:+UseSerialGC` |
| Worker | 约 240 MiB | `-Xmx192m -XX:+UseSerialGC` |
| Nginx | 约 15 MiB | — |

当前 Docker Compose 为 API 设置 384 MiB、Worker 设置 512 MiB 的容器内存上限；默认 Java 堆仍为 192 MiB，
Worker 并发为 1。canary 使用更低的 320/384 MiB 容器上限和 128 MiB 堆。容器只隔离 API 与 Worker；
MySQL、Kafka、Nginx 和 Jenkins 仍是宿主机服务。

按上述宿主机 JVM 的 RSS 实测，四个常驻组件合计约 1.0 GiB；加上云厂商代理约 1.4 GiB，
在 1962 MiB 下留有约 500 MiB 余量。Docker 资源上限是当前配置值，实际部署的 RSS 需由 Jenkins/服务器监控验证。

**峰值来源是构建，不是运行。** Gradle 测试 JVM 和 Node 构建会增加较多内存，因此代码编译、测试、
前端构建和发布包组装都在服务器 Jenkins agent 的流水线中完成。不要在开发机或 Codex 工作区本地编译。
集成阶段会停止 API/Worker 容器，保留测试需要的 MySQL 与 Kafka；流水线结束时会恢复当前服务。因此：

- `scripts/deploy-demo.sh` 只接收 Jenkins 已验证的二进制发布目录，不会重新编译源代码；
- Jenkins 在集成测试期间停止 API/Worker 容器，只保留 MySQL 与 Kafka；
- 交换分区 4 GiB **只作 OOM 保护**，不作为容量使用。`vm.swappiness=10`。

不要在这台机器上同时运行：两个构建、构建与转码、或构建与 Jenkins 构建。

## 3. 首次准备

```bash
sudo bash scripts/provision-host.sh
```

幂等。它会：安装发行版包（JDK 17/21、FFmpeg、Nginx、MySQL）、写入 MySQL 调优文件、
创建 `media_workspace` 与 `media_workspace_test` 库、授予 `mw_it_%` 通配权限（供集成测试自建 schema）、
安装并格式化单节点 KRaft Kafka、创建三个主题、创建存储目录，并把运行期配置与自动生成的密码写入
`/opt/media-workspace/config/media-workspace.env`（0640，属主 root:ubuntu）。

Docker Engine 与 Compose v2 由服务器维护，不由此脚本安装。发布前须确认 Docker daemon 正常、
Jenkins agent 能通过 `sudo docker` 管理容器，并且该 agent 对 `/opt/media-workspace/var/storage`
及日志目录有写权限。容器以该 agent 的 UID/GID 运行，保证持久 bind mount 可写。

该文件是唯一存放密码的地方，**不在仓库里，也不写进日志**。已经存在的键不会被重写，
因此重复执行不会把数据库密码换掉。

生成测试素材（无版权，全部由 FFmpeg 合成）：

```bash
bash scripts/generate-test-media.sh /opt/media-workspace/var/test-media
```

## 4. 构建与测试

所有构建和测试从 Jenkins 触发。选择责任模块并提供基线提交；发布构建必须运行集成测试，
不能通过参数跳过范围门禁、后端/前端测试或发布所需的集成测试。流水线依次执行契约与架构校验、
后端和前端测试、真实 MySQL/Kafka/FFmpeg 集成测试、制品组装。集成测试的凭据由服务器运行期配置注入，
不会写入仓库或构建命令行。每次运行使用独立 schema、存储目录和 Kafka 前缀。

## 5. 部署

通过 Jenkins 构建参数发布：设置有效的 `RELEASE_VERSION`、启用 `DEPLOY_DEMO`，并选择 `build-delivery`
责任模块。流水线必须完成集成测试和制品校验才会进入 DeployDemo；不要在开发机运行部署脚本。
`scripts/deploy-demo.sh` 只能消费当前 Jenkins workspace 中的已验证二进制包。

部署流程：校验完整 commit、干净工作树和 `SHA256SUMS` → 从两个可执行 JAR 构建独立 API/Worker 镜像
→ 在旧服务继续工作的同时，用独立 schema、Kafka topic 前缀、存储与日志目录启动候选容器
→ 对候选执行 API/Worker readiness 和完整上传、转码、播放 smoke → 清理 canary → 停止旧服务
→ 运行生产 schema migration → 切换 `current` → 启动新容器 → 经 Nginx 再跑完整 smoke。
Canary schema `mw_it_deploy_<token>` 先由 `sudo mysql` 创建为 `utf8mb4_0900_ai_ci`，
再由应用数据库账号运行 migration；清理时使用相同的本机 root socket 管理通道删除该 schema。
候选验证失败时旧版本继续服务；切换后的启动或 smoke 失败则自动恢复上一版本并重启旧服务。
**数据库不回滚**（migration 按向前兼容新增字段设计，反向 DDL 有风险）。

发布目录布局：

```
/opt/media-workspace/
  releases/<version>-<shortCommit>/{apps,libs,web,config,scripts,docker,manifest.json,SHA256SUMS}
    docker/{api,worker}/        各自的 Docker build context 与可执行 JAR
    docker/compose.yaml         API/Worker 运行定义
  current -> releases/<version>-<shortCommit>
  var/{storage,logs,run,test-media,kafka-logs}
  config/media-workspace.env
```

`manifest.json` 由构建脚本从实际产物生成：commit 取自 Git，工具版本取自工具本身，
每个摘要取自磁盘上的文件。工作区有未提交改动时会记录 `treeState: "dirty"` 与改动文件列表，
不会把改动伪装成某个干净的 commit。

## 6. 启停与状态

```bash
bash scripts/service.sh start|stop|restart|status api|worker|all [发布目录]
```

新发布由 Docker Compose 管理。脚本仅按固定的 Compose 项目与服务标签检查和停止本项目容器；
旧发布回滚仍兼容 PID 管理，停止前会核对 `/proc/<pid>/stat` 中的进程启动时间，拒绝操作被复用的 PID。
**不会按进程名杀进程，也不操作其他 Compose 项目。**

日志：

```
/opt/media-workspace/var/logs/api/console.log     人类可读
/opt/media-workspace/var/logs/api/media-api.json  每行一个 JSON 对象
/opt/media-workspace/var/logs/worker/...
```

`media-api.json`、`media-worker.json` 由宿主机 bind mount 持久保存；容器 stdout/stderr 可通过
`sudo docker logs <容器>` 查看（`service.sh status` 会列出容器 ID）。`console.log` 是旧 PID 发布的日志路径。

JSON 日志里的 `traceId`、`taskId`、`generation`、`attempt`、`executionEpoch`、`workerId`
用于把一次任务链路串起来：

```bash
grep '"taskId":"<任务编号>"' /opt/media-workspace/var/logs/worker/media-worker.json | head
```

## 7. 常用排查

**任务一直停在 WAITING_EVENT**：请求事件没被消费。依次看 outbox 是否已发出、
Kafka 主题名是否与生产端一致、Worker 是否在跑：

```bash
set -a; . /opt/media-workspace/config/media-workspace.env; set +a
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
  -e "SELECT state, topic, publish_attempt FROM outbox_event ORDER BY created_at DESC LIMIT 5"
/opt/kafka_2.13-3.9.2/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list
grep KAFKA_TOPIC_PREFIX /opt/media-workspace/config/media-workspace.env
```

生产端与消费端必须解析出同一个主题名。前缀只在发送那一刻应用一次。

**任务停在 RUNNING 不动**：Worker 的租约续期没跑或 Worker 已死。15 秒内
`TaskRecoveryService` 会把过期租约记为 LOST 并重排或置 FAILED。

**上传返回 503**：存储卷不可用。适配器在启动时就校验根目录可写，启动成功后再出现 503
说明磁盘满了或卷被卸载：

```bash
df -h /opt/media-workspace; tail -5 /opt/media-workspace/var/logs/api/media-api.json
```

**机器失去响应**：几乎总是构建与常驻服务同时在跑。等待构建结束；恢复后按第 4 节的做法错峰。

## 8. 备份与恢复

```bash
# 停写（停 API 与 Worker，保留 MySQL 运行）
bash scripts/service.sh stop all
# 备份：数据库与存储卷必须属于同一个备份编号
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
mysqldump --single-transaction -h127.0.0.1 -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
  | gzip > /opt/media-workspace/var/backup/db-$STAMP.sql.gz
tar -czf /opt/media-workspace/var/backup/storage-$STAMP.tar.gz -C /opt/media-workspace/var storage
```

只备份数据库无法恢复媒体：文件字节不在库里。恢复时数据库与存储卷必须来自同一 `STAMP`。
恢复到**隔离**的库与新目录验证，不覆盖现有环境。

## 9. 故障演练

`scripts/ci-prepare.sh` 会停掉演示服务以便构建；演练结束后用
`bash scripts/service.sh start all` 恢复，再跑 `bash scripts/smoke-test.sh` 确认链路仍然通。

演练前先确认没有正在进行的构建，避免与构建争抢内存。
