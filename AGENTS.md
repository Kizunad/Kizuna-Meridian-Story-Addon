# 仓库协作约定

- 本仓负责 Minecraft Java 26.2 的剧情线、章节进度、信物凭据和玩家对话。出身定义由 Origin 管理，来犯的排期和战果由生人引擎裁定，身体规则归对应宿主。
- 完整装配依赖 Framework、Origin、生人、功法库、Taunts 和 Fabric API。玩家进度只经身体宿主保存，战果在章节进度提交之后确认。
- `shared/` 放 Protobuf 契约，`server/` 放纯规则，`fabric/` 放数据包加载、生命周期、物品交互、原版聊天和选项复核。验收剧情放 GameTest 源集。
- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。代码按读取、校验、计算、请求、结果展开，命名、换行和注释密度与相邻代码保持一致。
- 文档唯一编辑源为 `docs/wiki/`，README 仅作入口。交付执行 Fabric GameTest、Xvfb 客户端截图测试、文档引用检查和 `git diff --check`。
- 未获授权不提交、推送或远端建仓。许可为 PolyForm Noncommercial 1.0.0，保留第三方许可。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
