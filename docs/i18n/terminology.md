# Terminology

本表约定本仓库的中英术语统一译法。

**通用规则：**
- "中文"列为中文译文的正文默认用词。若该列为英文，则中文译文的正文中保留英文不翻译。
- 首次出现按"首次出现"列书写（带括号注释）；后续出现只写括号前的部分（可能为中文，也可能为英文），不出现括号内的注释。
- "不要译作"列为严格禁止的译法。
- 如果某术语已经作为另一个术语的组成部分被括注过，则该术语后续单独出现时无需再次括注。

术语表只记录稳定的翻译决策，不是第二份 API 目录。类型、行为和边界应链接到其所属的子系统或用户文档。

## 缩写类（中英文文本中均使用缩写）

| English | 中文 | 首次出现 | 不要译作 | 备注 |
|---|---|---|---|---|
| API | API | 应用程序接口（API） | 接口（用于指 API） | API、endpoint 和 interface 按上下文区分。 |
| UI | UI | 用户界面（UI） | 用户接口 | 保留 UI 缩写。 |
| OAuth | OAuth | OAuth 认证 | | 保留协议名。 |
| PKCE | PKCE | PKCE（Proof Key for Code Exchange） | | 保留规范缩写。 |
| R18 | R18 | R18 内容 | | 保留 Pixiv 约定。 |

## 英文类（中英文文本中均使用英文）

| English | 中文 | 首次出现 | 不要译作 | 备注 |
|---|---|---|---|---|
| Gradle wrapper | Gradle wrapper | Gradle wrapper | Gradle 包装器 | 保留命令入口名称。 |
| Keychain | Keychain | macOS Keychain（钥匙串） | 密钥链 | 系统服务名称保留英文。 |
| screen model | screen model | screen model（页面模型） | 屏幕模型 | 指 Voyager/Compose 页面对应的状态和异步逻辑对象。 |
| sidecar | sidecar | sidecar（一致性记录） | 附属文件 | 指 `.i18n.yaml` 文件。 |
| subsystem | 子系统 | 子系统（subsystem） | 模块（用于文档边界） | 仅指稳定的运行时边界。 |

## 双语类（中英文文本各自使用中英文）

| English | 中文 | 首次出现 | 不要译作 | 备注 |
|---|---|---|---|---|
| architecture map | 架构地图 | 架构地图（architecture map） | 架构图 | 指 `docs/architecture.md` 及其中文对应页。 |
| Agent Note | Agent Note | Agent Note（决策记录） | 代理笔记 | 指 `.agents/notes/` 中的生命周期记录。 |
| postmortem | 事故复盘 | 事故复盘（postmortem） | 事后报告 | 只用于达到记录边界的系统性失败。 |
| direct connect | 直连 | 直连（direct connect） | 直接连接 | 指 Pixiv 网络传输配置。 |
| download queue | 下载队列 | 下载队列（download queue） | 下载列表 | 指持久化下载任务队列。 |

## 待定术语

没有稳定译法的术语先保留英文，并记录建议译法、证据和决定状态。确定译法后，将它移动到上面的对应分类中。

当前没有影响活动文档评审的待定术语。
