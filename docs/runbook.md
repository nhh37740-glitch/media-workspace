# 运行手册

面向在这台演示主机上部署、启动、排查的人。命令都假设当前目录是仓库根目录，
`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`。

## 1. 主机与端口

主机：`ubuntu@43.153.176.182`，2 vCPU / 1962 MiB 内存 / 59 GB 磁盘，Ubuntu 26.04。
SSH 主机指纹记录在 `docs/ssh-host-fingerprint.txt`，如与本机记录冲突应停止连接并核对。

| 组件 | 监听 | 对外 |
|---|---|---|
| Nginx | 0.0.0.0:8088 | 是，唯一公开端口 |
| API | 127.0.0.1:8080 | 否 |
| Worker 健康端点 | 127.0.0.1:8090 | 否，只提供 actuator |
| MySQL | 127.0.0.1:3306 | 否 |
| Kafka | 127.0.0.1:9092 | 否 |
| Jenkins | 127.0.0.1:8081 | 否，需要时用 SSH 隧道 |

从本机访问 Jenkins：`ssh -L 8081:127.0.0.1:8081 -i codex.pem ubuntu@43.153.176.182`，
然后浏览器打开 `http://127.0.0.1:8081`。

## 2. 实测内存预算

这台机器上还有云厂商的代理进程（`tat_agent`、`YDService` 等）常驻约 400 MiB，**不属于本项目，
不能停**。因此可用内存约 1.5 GiB。

2026-09-25 实测的常驻占用（RSS，`ps -eo rss`）：

| 组件 | 实测 | 配置 |
|---|---|---|
| mysqld | 约 280 MiB | `innodb_buffer_pool_size=96M`，`performance_schema=OFF` |
| Kafka (KRaft) | 约 200–320 MiB | `-Xmx256m` |
| API | 约 240 MiB | `-Xmx192m -XX:+UseSerialGC` |
| Worker | 约 240 MiB | `-Xmx192m -XX:+UseSerialGC` |
| Nginx | 约 15 MiB | — |

四个常驻组件合计约 1.0 GiB，加上云厂商代理约 1.4 GiB，在 1962 MiB 下留有约 500 MiB 余量。

**峰值来源是构建，不是运行。** 一次 `./gradlew clean check bootJar jar` 会再拉起
Gradle 守护进程与测试 JVM，加上 Node 构建，实测在服务同时运行时会把机器压到 SSH 握手超时。
因此：

- `scripts/deploy-demo.sh` **先停服务再构建**；
- `scripts/ci-prepare.sh` 在 Jenkins 的构建阶段同样先停服务，只保留 MySQL 与 Kafka
  （集成测试需要它们）；
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

该文件是唯一存放密码的地方，**不在仓库里，也不写进日志**。已经存在的键不会被重写，
因此重复执行不会把数据库密码换掉。

生成测试素材（无版权，全部由 FFmpeg 合成）：

```bash
bash scripts/generate-test-media.sh /opt/media-workspace/var/test-media
```

## 4. 构建与测试

```bash
# 单元测试 + 两个可执行 JAR + 库 JAR。不需要外部服务。
./gradlew clean check bootJar jar

# 真实 MySQL / Kafka / FFmpeg 的集成测试。每次运行自建独立 schema 与存储目录。
bash scripts/run-integration-tests.sh

# 只跑某个模块的集成测试
bash scripts/run-integration-tests.sh :adapter-persistence:integrationTest

# 转码适配器的集成测试（需要测试素材）
MW_TEST_MEDIA=/opt/media-workspace/var/test-media ./gradlew :adapter-transcode:integrationTest

# 架构依赖方向与版本锁定
./gradlew architectureCheck versionLockCheck

# 契约校验（schema、样例、拒绝用例、feature 引用）
python3 scripts/validate-contracts.py

# 测试数量统计；零测试视为失败
python3 scripts/count-test-results.py --require-nonzero

# 单模块修改范围门禁
bash scripts/check_change_scope.sh --base <SHA> --head <SHA> --module <模块名>
```

集成测试的凭据通过环境变量传入，由 `scripts/run-integration-tests.sh` 从运行期配置文件导出。

## 5. 部署

```bash
bash scripts/deploy-demo.sh
```

流程：停服务 → 构建发布目录 → 校验 SHA256SUMS → 迁移 schema（独立进程，不经过两个服务）
→ 切换 `current` 符号链接 → 启动 → 等待就绪 → 冒烟；冒烟失败则把符号链接切回上一个版本，
**数据库不回滚**（迁移按向前兼容新增字段设计，反向 DDL 有风险）。

发布目录布局：

```
/opt/media-workspace/
  releases/<version>-<shortCommit>/{apps,libs,web,config,scripts,manifest.json,SHA256SUMS}
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

脚本只操作自己记录的 PID：停止前会核对 `/proc/<pid>/stat` 里的进程启动时间与记录值是否一致，
不一致就认为 PID 被复用、拒绝操作。**不会按进程名杀进程**，这台机器上其他项目的 JVM 不受影响。

日志：

```
/opt/media-workspace/var/logs/api/console.log     人类可读
/opt/media-workspace/var/logs/api/media-api.json  每行一个 JSON 对象
/opt/media-workspace/var/logs/worker/...
```

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
df -h /opt/media-workspace; tail -5 /opt/media-workspace/var/logs/api/console.log
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
