# 零听 (LingTing) — CLAUDE.md

## 项目概述
极简 Android 听书 App，通过 Alist WebDAV 连接网盘（如夸克网盘），**在线播放**听书资源，不下载到本地。

## 技术栈
- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **播放引擎**: Media3 (ExoPlayer)
- **数据库**: Room (本地 SQLite)
- **DI**: Hilt
- **网络**: OkHttp (WebDAV PROPFIND/GET/Range)
- **架构**: MVVM + Repository
- **最低 SDK**: API 26 (Android 8.0)
- **构建**: Gradle + GitHub Actions (自动构建 APK)

## 核心架构

```
com.lingting/
├── data/
│   ├── local/         # Room 数据库 (DAO, Entity)
│   ├── repository/    # 仓库层 (BookRepository, WebDavRepository)
│   └── model/         # 数据模型 (Book, WebDavFile)
├── network/
│   ├── WebDavClient.kt         # WebDAV 协议客户端
│   └── AuthDataSourceFactory.kt # Media3 鉴权数据源
├── playback/
│   ├── AudioPlayer.kt          # 窄播放器接口（ISP/DIP，测试用 Fake 实现）
│   ├── ExoAudioPlayer.kt       # ExoPlayer 适配器（Media3 类型不外泄）
│   └── PlaybackState.kt        # 全局播放快照（书架实时进度）
├── service/
│   └── PlaybackService.kt      # 后台播放 (MediaSessionService)
├── ui/
│   ├── server/        # WebDAV 服务器配置页
│   ├── browser/       # 网盘文件浏览页
│   ├── bookshelf/     # 书架页（含底部迷你播放条）
│   ├── player/        # 播放器页
│   ├── accounts/      # 账号管理页
│   ├── navigation/    # 导航路由
│   ├── theme/         # 暖陶土主题（深色模式跟随系统）
│   └── components/    # 共享组件（BookCover 书名首字封面）
└── di/                # Hilt 依赖注入模块
```

## 核心数据流

```
用户配置 WebDAV → 浏览目录 → 添加到书架(存URL) → 在线播放(Media3 GET+Range) → 每15秒自动保存进度
```

- **书架存储**: Room 数据库，仅存 WebDAV URL，不存音频文件
- **播放**: Media3 直接通过 HTTP Range 流式播放，不需要本地存储权限
- **进度记忆**: 每 15 秒自动保存 + 拖动进度条立即保存 + 退出/切换时保存
- **不实现**: 本地文件导入、文件下载、TTS 朗读、B 站在线集成

## 关键依赖版本

| 依赖 | 版本 |
|------|------|
| AGP | 8.4.0 |
| Kotlin | 1.9.22 |
| Compose BOM | 2024.01.00 |
| Media3 | 1.2.1 |
| Room | 2.6.1 |
| Hilt | 2.50 |
| OkHttp | 4.12.0 |
| Gradle | 8.6 |

## 构建命令

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew assembleRelease        # 构建 Release APK
```

GitHub Actions 自动构建输出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 开发约定

- 遵循 MVVM 模式，ViewModel 通过 Hilt 注入
- UI 使用 Compose Material 3，不使用 View 系统
- 所有网络请求通过 Repository 层封装
- 数据库操作通过 Room DAO + Repository 封装
- 新增功能遵循 KISS/YAGNI 原则保持极简

## 单元测试（UT）强制要求与 TDD 流程

**所有新增功能与 Bug 修复，必须同时实现单元测试（UT）。** 无 UT 的代码视为未完成。

- 采用 **TDD（测试驱动开发）** 思想：**先写测试，再写实现**。
- 标准流程：
  1. 明确需求与边界条件 → 先编写对应 UT（此时应能编译/运行失败，因为实现尚未完成）。
  2. 再编写产品代码，使测试通过。
  3. 重构并保持测试全绿。
- **UT 覆盖范围**（至少包含）：
  - 纯逻辑/解析类（如 XML/JSON 解析、路径归一、数据转换）必须有对应测试。
  - Repository、ViewModel 的关键业务分支（成功/失败/边界）。
  - 数据库迁移（Migration）的正确性。
- **测试位置与命名**：
  - 单元测试放在 `app/src/test/java/<包路径>/` 下，与源文件包结构一致。
  - 测试类命名为 `<被测类名>Test`，方法命名为 `should_预期_当_场景`。
- **运行命令**：
  ```bash
  ./gradlew testDebugUnitTest        # 运行全部单元测试
  ./gradlew testDebugUnitTest --tests "*WebDavPropfindParserTest"  # 运行指定测试
  ```
- 提交前必须确保 `./gradlew testDebugUnitTest` 全部通过。
