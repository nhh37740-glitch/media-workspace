# 本项目操作约束

- 不使用浏览器应用商店。
- 不使用 `browser-harness`。
- 浏览器操作优先使用本机 Microsoft Edge，其次使用本机 Chrome。
- 当前访问 GPT/Codex API 依赖 VPN；本项目不处理、不排查 VPN 相关问题。
- Windows 鼠标控制使用 `computer-use` 插件的 `@oai/sky` 接口；先 `list_apps()`，再选择返回的 Edge 窗口。旧 `cua_repl` 浏览器桥曾返回 `nodeRepl.fetch request failed`，不能据此判断 Edge 未安装或不可控。
- 若 `computer-use` 报告无法可靠确定当前浏览器 URL 并终止本轮操作，应停止该轮 UI 输入；这是控制工具的 URL 验证失败，不要继续复用窗口坐标。
- 定位问题后按责任模块委派 subagent；每个 subagent 只负责一个模块，不让多个 subagent 同时修改同一模块。主 agent 负责范围核对、整合与验证。
- subagent 以负责的模块或业务命名（例如 `build-delivery / Jenkins`），不得使用随机名称；如果工具自动生成昵称且不支持自定义，应使用明确的模块/业务标题和任务说明，并在汇报中按该职责称呼。
