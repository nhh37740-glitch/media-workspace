# 交接文档

日期：2026-09-25。分支 `main`，最后提交 `20a14ce`（其后 `65277d1` 为文档提交，`git log` 为准）。

---

## 1. 代码在哪

| 内容 | 位置 |
|---|---|
| 仓库 | `X:\javaproject\media-workspace`（Windows 本机，X: 盘） |
| 服务器副本 | `ubuntu@43.153.176.182:~/media-workspace`（含 `.git`） |
| SSH 私钥 | `X:\javaproject\codex.pem` |
| 同步脚本 | `X:\javaproject\sync-media-workspace.sh push` |
| 带重试的 SSH 封装 | `X:\javaproject\ssh-mw.sh '<命令>'`（sshd 约五次里成功一次，必须用它） |
| 计划书 | `X:\javaproject\团队影音素材平台计划书\` |

**X: 盘不稳定，本次会话中消失过两次，每次约 40 秒后自行恢复。** 消失期间仓库与私钥都不可访问。
服务器副本可作备份，但服务器上可能缺少最近一次本机提交。

### 1.1 远程访问方法

主机：`43.153.176.182`，用户 `ubuntu`，密钥认证。在 Windows 本机的 Bash 里：

```bash
# 直接连接（会经常失败，见下）
ssh -i "X:\javaproject\codex.pem" ubuntu@43.153.176.182

# 推荐：封装脚本，只重试连接失败，命令本身失败会立即返回
bash "X:/javaproject/ssh-mw.sh" 'uptime'
bash "X:/javaproject/ssh-mw.sh" <<'EOF'
多行脚本直接写在这里
EOF

# 同步本地源码到服务器（tar over ssh，含 .git；排除构建产物与凭据）
bash "X:/javaproject/sync-media-workspace.sh" push
bash "X:/javaproject/sync-media-workspace.sh" pull <服务器上的相对路径>
```

**为什么必须用封装脚本**：这台主机的 sshd 大约五次里只成功一次，其余在握手中返回
`Connection closed/reset by ... port 22`，而机器本身负载很低（实测 load 0.19）。
`ssh-mw.sh` 只对这种握手失败重试，命令真正执行后失败则立即报错，不会重复执行。

**主机指纹**：ED25519 `SHA256:sTpylg4cljm9w2gKt6Wlw70mswZp5BOYEHuScuX5P/Y`，
也记在仓库 `docs/ssh-host-fingerprint.txt`。**与这个值不一致就停止连接并核对**，
不要用 `StrictHostKeyChecking=no` 绕过。

**回环服务怎么访问**：API 8080、Worker 8090、MySQL 3306、Kafka 9092、Jenkins 8081
都只监听 127.0.0.1，从本机访问需要隧道，例如 Jenkins：

```bash
ssh -i "X:\javaproject\codex.pem" -L 8081:127.0.0.1:8081 ubuntu@43.153.176.182
# 然后浏览器打开 http://127.0.0.1:8081
```

公网只有一个入口：`http://43.153.176.182:8088`（Nginx）。注意云厂商安全组是否放行 8088；
如果没放行，上面同样可以用 `-L 8088:127.0.0.1:8088` 隧道访问。

**凭据在哪**：数据库密码、演示账号密码、Jenkins 管理员密码全在服务器的
`/opt/media-workspace/config/media-workspace.env`（0640，属主 root:ubuntu，`ubuntu` 可读）。
**仓库里没有任何凭据**，私钥 `codex.pem` 也只在 `X:\javaproject\` 下，两者都不进版本库、不进日志。
在服务器上读取：`sudo grep '^DB_' /opt/media-workspace/config/media-workspace.env`。

---

## 2. 服务器现状（离开时的状态）

- 已部署版本：`/opt/media-workspace/current` → `releases/0.1.0-20a14ce`
- **API 与 Worker 处于停止状态**：为了给前端构建腾内存执行了 `scripts/ci-prepare.sh`，
  之后未恢复。恢复命令：`bash ~/media-workspace/scripts/service.sh start all`
- MySQL、Kafka、Nginx 正常运行；Jenkins 控制器在运行（回环 8081），但见第 5 节
- 端口：Nginx `0.0.0.0:8088` 是唯一公开入口；API `127.0.0.1:8080`、Worker `127.0.0.1:8090`、
  MySQL `127.0.0.1:3306`、Kafka `127.0.0.1:9092`、Jenkins `127.0.0.1:8081`

---

## 3. 已经实际验证跑通的

证据都是真实执行的输出，不是推断。

**完整业务链路**（`scripts/smoke-test.sh`，在 `0.1.0-20a14ce` 上通过）：

```
登录 → 创建空间 → 分片上传真实 mp4 → 同幂等键重放返回同一会话
→ complete → 合并 → 任务 RUNNING 3% → SUCCEEDED 100%
→ 播放 211554 字节 → Range 请求 206 → 封面 200
→ 创建分享 → 越会话 CSRF 被拒 → 分享播放 211554 字节 → 撤销后新请求 404
→ 登出后会话失效
SMOKE TEST PASSED
```

**测试**（全部实际运行）：

| 命令 | 结果 |
|---|---|
| `./gradlew clean check bootJar jar` | BUILD SUCCESSFUL，12 份报告，**90 项单元测试，0 失败 0 跳过** |
| `:tests:e2e:integrationTest` | **18/18 PASSED**（MSG-01 重复事件、JOB-02/03/05/07/08/09、LOAD-01 容量上限） |
| `:adapter-persistence:integrationTest` | 5/5 PASSED（配额并发竞争、成员可见性、配额计数） |
| `:adapter-transcode:integrationTest` | 14/14 PASSED（真实 FFmpeg：竖屏、720p、stderr 洪水、超时、取消杀进程树、半成品拒绝） |
| `:adapter-storage:test` | 20/20 PASSED（路径越界、符号链接逃逸、不可变发布） |

**制品**：`releases/0.1.0-20a14ce/`，`manifest.json` 记录 `treeState: clean`、
commit `20a14cedd5af722d3f509584be0b9b0cfaa65f26`、17 个产物、schema v2、事件 schema v1、
JDK 21 / Node 22.23.3 / FFmpeg 8.0.1。

**测试期间发现并修复的 8 个真实缺陷**，每个都有回归测试，清单在 `docs/test-matrix.md` 第九节。
其中两个是端到端才暴露的接线错误（Kafka 主题前缀两端不一致、`@KafkaListener` 类未注册为 Bean），
一个是恢复路径用错条件更新导致任务永远停在 RUNNING（见 `docs/contract-changes/CONTRACT_CHANGE-001`），
一个是 API 绑在 `0.0.0.0:8080` 而非回环。

---

## 4. 未完成的事（重要）

### 4.1 前端从未构建成功，Nginx 返回 403

`web/` 下有完整的 Vue 3 源码（四个页面 + 分享落地页 + 单元测试），但：

- **`web/package-lock.json` 不存在**，所以 `scripts/build-release.sh` 跳过了前端构建，
  发布目录里的 `web/` 是空的；
- 尝试 `npm install` 生成 lockfile 时报
  `npm error Cannot read properties of null (reading 'edgesOut')` 后失败，
  原因未定位（怀疑是残留的 `node_modules` 与 npm 10.9.9 的已知问题）；
- 因此访问 `http://43.153.176.182:8088/` 得到 Nginx 的 403，页面无法使用。

**后端与前端的接口是通的**（冒烟脚本走的就是 HTTP API），缺的只是前端产物本身。

建议的排查顺序：`rm -rf ~/media-workspace/web/node_modules` 后重试 `npm install`；
若仍失败，把 npm 升到 11/12，或删掉 `web/package.json` 里 `engines` 之外的干扰项。

### 4.2 Jenkins 没有跑过任何一次流水线

已安装 Jenkins 2.568.3（控制器回环 8081、0 执行器、Agent 节点已定义、systemd 单元与
`deploy/jenkins/init.groovy.d/` 初始化脚本都已提交），但：

- 管理员凭据认证始终返回 **401**；
- 曾删除 `/opt/jenkins/users` 与 `config.xml` 后重启，账号与 realm 仍被重现，**根因未确定**；
- 插件因此无法通过 CLI 安装，`workflow-aggregator` 缺失，流水线跑不起来。

子代理在失败前定位到一点：CLI 的 `/cli?remoting=false` 端点用的是 HTTP Basic，
凭据被拒时返回 401（权限不足则是 403），所以 401 就是"密码不匹配"而非权限问题。
它还发现 `/tmp/authfile` 曾被写成 curl 配置格式（`user = "admin:..."`），
CLI 会把整行当作密码——如果后续再排查，先确认凭据传递格式。

**结论：CI-01 ~ CI-04、MOD-02/MOD-03 的 Jenkins 执行形式全部 NOT_RUN。**
Jenkinsfile 里的命令与本地完全一致，本地已全部跑通，但这不能替代由 Jenkins 驱动的执行。

### 4.3 其他 NOT_RUN 的用例

完整清单在 `docs/test-matrix.md` 第五节，主要包括：MSG-02/03/04/06（outbox 崩溃重发、
消费提交后确认前退出、消费回滚、Kafka 停机恢复）、JOB-06（同重试键并发）、PLAY-03（浏览器拖动）、
SSE-01/02（断线重连、慢客户端）、LOG-01~03（trace 串联断言）、DATA-02/03/04（EXPLAIN 对比、
GC 并发、备份恢复）、LOAD-02（双 Worker 对比）、ACL-03（移出成员关闭 SSE）。

---

## 5. 环境注意事项

1. **内存是硬约束。** 2 vCPU / 1962 MiB，云厂商代理（`tat_agent`、`YDService`）常驻约 400 MiB，
   **不能动**。构建与常驻服务**不能并行**：曾观测到 Gradle 构建与两个应用 JVM 同时运行时，
   机器在交换分区上抖到 SSH 握手超时、主机不可用约一小时。
   规则：构建/集成测试/Jenkins 之前先跑 `scripts/ci-prepare.sh`（它只停自有服务，
   MySQL 与 Kafka 保留给集成测试用）。
2. 实测常驻占用：MySQL ~120 MiB、Kafka ~137 MiB、API ~280 MiB、Worker ~247 MiB、
   Jenkins ~243 MiB、Nginx ~15 MiB。
3. `scripts/service.sh` 只操作自己记录的 PID，停止前核对 `/proc/<pid>/stat` 的启动时间，
   **不按进程名杀进程**。这台机器上还有别的东西在跑。
4. SSH 不稳定，用 `ssh-mw.sh`；直接 ssh 会看到 `Connection closed/reset`。

---

## 6. 继续开发的最短路径

```bash
# 0. 确认 X: 盘在（它不稳定）
ls X:/javaproject/media-workspace

# 1. 恢复演示服务
bash /x/javaproject/ssh-mw.sh 'cd ~/media-workspace && bash scripts/service.sh start all'

# 2. 补前端（第 4.1 节）
bash /x/javaproject/ssh-mw.sh 'cd ~/media-workspace && bash scripts/ci-prepare.sh && cd web && rm -rf node_modules && npm install && npm test -- --run && npm run build'
# 成功后把 package-lock.json 拉回本机并提交

# 3. 重新部署（现在会把前端打进发布包）
bash /x/javaproject/ssh-mw.sh 'cd ~/media-workspace && bash scripts/deploy-demo.sh'
```

其余命令、排查步骤、备份恢复见 `docs/runbook.md`；设计选择见 `docs/decisions.md`；
模块边界与修改纪律见 `CLAUDE.md` 与计划书 09 号规范。

---

## 7. 一句话总结

后端链路是完整可跑的，测试是真实跑过且绿的，缺陷是真实定位并修复的；
**前端产物与 Jenkins 流水线这两块没有交付**，原因与排查方向都在上面写清了，没有含糊。
