# 仓库协作约定

- 本仓库只收 Minecraft Java 版 26.2 的出身框架：出身数据包的读取、类型登记入口、抽选、玩家记录、选择界面、指令和进度触发器。具体天赋和威胁的实现归管那块机制的附属，不放进本仓；首批出身的 JSON 和叙事也不在本仓。
- 模组依赖只有 Framework、Fabric API 和 owo-lib。玩家记录经 Framework 的两个身体宿主入口读写，不依赖 Human；别的附属要登记自己的类型，都反过来依赖本仓，所以本仓不能依赖它们任何一个。
- Meridian 各附属按完整装配运行；不得添加缺少必需附属时的替代路径、降级或可选依赖判断。
- `shared/` 只放协议；`server/` 保存与 Minecraft 无关的抽选、记录规则和配置；`fabric/` 负责登记入口、数据包、身体存取、网络、指令、触发器和客户端界面。
- 出身定了不能改。调试指令可以设，只为测试；不要加任何游戏内改出身的途径。
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
