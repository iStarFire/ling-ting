# 听一听 (TingYiTing) — CLAUDE.md

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
com.tingyiting/
├── data/
│   ├── local/         # Room 数据库 (DAO, Entity)
│   ├── repository/    # 仓库层 (BookRepository, WebDavRepository)
│   └── model/         # 数据模型 (Book, WebDavFile)
├── network/
│   ├── WebDavClient.kt         # WebDAV 协议客户端
│   └── AuthDataSourceFactory.kt # Media3 鉴权数据源
├── service/
│   └── PlaybackService.kt      # 后台播放 (MediaSessionService)
├── ui/
│   ├── server/        # WebDAV 服务器配置页
│   ├── browser/       # 网盘文件浏览页
│   ├── bookshelf/     # 书架页
│   ├── player/        # 播放器页
│   └── navigation/    # 导航路由
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
