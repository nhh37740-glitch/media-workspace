# 只读游客入口（2026-10-09，待服务器验证）

新增 POST `/api/v1/auth/guest`，不接受密码、不在前端保存或使用默认凭据；通过既有 CSRF 流程建立 Spring SESSION。`UserView` 增加服务器推导的 `guest` 布尔值，供界面展示只读模式。密码登录仍创建普通账号会话，游客可在退出后使用拥有者账号管理素材。

默认关闭，只有 `mediaworkspace.guest.enabled=true` 和 `mediaworkspace.guest.user-id` 固定为服务器已核验的 viewer UUID 才能开放。后端再次核验该 UUID 的 username 必须是 viewer、账号已启用；不存在、错配或禁用均拒绝。游客保留普通成员读取规则，只能浏览已授予 VIEWER 成员关系的演示空间，不授予所有空间或其他账号的数据访问。不能由客户端字段或自填请求头获得该权限。

ROLE_GUEST 随 SecurityContext 存入现有 MySQL session，后续每个请求都由服务器识别。所有非 GET/HEAD/OPTIONS/TRACE 操作对游客拒绝，包括创建空间、上传、分片、重命名、删除、创建分享、成员管理、任务取消/重试；唯一例外是已有 CSRF 保护的密码登录与退出登录。前端隐藏写按钮只改善使用体验，不能替代该边界。游客和密码登录显式更换 session id，退出立即清除会话。

验收必须由服务器 Docker/Jenkins 运行：CSRF、标记持久化、session 轮换、账号 pinning、游客写入拒绝、账号升级和退出；原领域成员权限测试与 Web 测试保留。部署配置及演示空间成员授权由部署负责人核验，源码完成不代表线上已开放。此修改从本地 `6d3d7f1` 开发；服务器主线为 `2245350`，仅重放本模块提交，保留服务器已有部署变更。
