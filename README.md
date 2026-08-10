<div align="center">

<img src="icon-preview.svg" width="120" alt="LingTing Logo">

# 零听 · LingTing

**极简 Android 听书 App —— 通过 WebDAV（兼容 Alist）连接网盘，在线流式播放听书资源，支持书架管理、进度记忆、跳过头尾与睡眠定时。**

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/minSdk-26-blue)]()
[![Target SDK](https://img.shields.io/badge/targetSdk-34-blue)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](#许可证)

</div>

## 这是什么

**零听（LingTing）** 是一款极简的 Android 听书客户端。它让你通过 **WebDAV 协议**（兼容 Alist 等常见网盘网关）直接访问云端音频资源，**在线流式播放**，不占用手机本地存储。所有书架元数据和播放进度都保存在本机 Room 数据库中，App 本身只是一个"播放器 + 书架"。

> 灵感：让听书回到"听"的本质 —— 打开就能听，关闭无残留。

## 核心特性

### 🌙 听书体验
- **后台播放 + MediaSession** —— 锁屏、通知栏、灵动岛、车载蓝牙均可控制
- **在线流式播放（HTTP Range）** —— 音频文件不下载到本地，随点随听
- **进度自动记忆** —— 每 15 秒自动保存进度，切集、退出 App 后无缝接续
- **跳过头尾（片头片尾跳过）** —— 滑杆自定义 + 快捷档位（15s/30s/60s/120s/180s），整专辑生效
- **睡眠定时器** —— 倒数剩余时间自动暂停

### 📚 书架与导入
- **单文件导入** —— 选中 WebDAV 中的一个音频文件即可加入书架
- **批量目录导入** —— 递归遍历整个目录，自动按文件名自然排序生成多集播放列表
- **封面艺术图** —— 自动生成暖色调封面，支持自定义替换
- **选集列表** —— 多集作品在播放页内可快速切换

### 🔐 数据与隐私
- **WebDAV 凭证加密存储** —— 使用 EncryptedSharedPreferences 保存密码
- **本地优先** —— 书架、进度、配置全部在本地，无任何远端统计/上报
- **多账号支持** —— 可同时管理多个 WebDAV 源

### 🎨 设计与 UI
- **Jetpack Compose + Material 3** 全新暖陶土主题
- **迷你播放条** —— 书架底部随时返回当前播放
- **底部导航 + 抽屉式播放页** —— 流畅的手势交互

## 架构与代码

```
app/src/main/java/com/lingting/
├── ui/                # Compose UI（bookshelf / player / browser / server / accounts）
├── data/
│   ├── local/         # Room 数据库、DAO、Entity（含 4 次迁移）
│   ├── repository/    # 业务协调层
│   └── model/         # 领域模型
├── network/           # WebDAV 客户端、PROPFIND 解析器、动态鉴权
├── service/           # PlaybackService（MediaSession 后台服务）
└── di/                # Hilt 依赖注入
```

- MVVM + Repository + Hilt
- 状态：Kotlin Coroutines + StateFlow
- 依赖注入：Hilt
- 单一播放引擎：Media3 ExoPlayer + 自定义 `DynamicAuthDataSourceFactory` 让流式请求自动携带 WebDAV 凭证
- 数据库迁移：Room v1→v2→v3→v4→v5 显式迁移脚本

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言 | Kotlin 1.9.22 |
| UI | Jetpack Compose · Material 3 |
| 播放 | AndroidX Media3 (ExoPlayer + MediaSession) |
| 数据库 | Room 2.6.1 |
| 网络 | OkHttp 4.12 + 自研 WebDAV PROPFIND/GET |
| 依赖注入 | Hilt 2.50 |
| 加密存储 | androidx.security:security-crypto |
| 构建 | Gradle 8.6 · AGP 8.4.0 · JDK 17 |

## 构建与运行

### 前置要求
- Android Studio Hedgehog (2023.1.1) 或更新
- JDK 17

### 命令行构建 Debug APK

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 运行 JVM 单元测试

```bash
./gradlew testDebugUnitTest
```

测试覆盖：WebDAV PROPFIND 解析器（命名空间、href 归一化、UTF-8 解码）、目录导入行为、播放器 ViewModel 跳过头尾/续播等核心逻辑。

## 发布与 CI

项目内置 GitHub Actions 自动发布流水线（`.github/workflows/release.yml`）：

1. 推送形如 `v1.0.0` 的 tag
2. CI 自动用正式 keystore 签名构建 APK
3. 自动提取 tag 区间的 commit 生成 changelog
4. 发布到 GitHub Releases，APK 命名为 `LingTing-v1.0.0.apk`

详细配置见工作流文件注释。

## 路线图

- [x] 跳过头尾（片头/片尾）
- [x] 多集目录批量导入
- [x] 媒体通知 + 灵动岛
- [x] 暖陶土主题与全新 UI
- [x] 封面艺术图
- [ ] 书架分组 / 标签
- [ ] 睡眠定时器自定义时长（当前固定档位）
- [ ] 内置简易搜索

## 参与贡献

欢迎 PR 与 Issue。建议在提 PR 前：

1. 先开 Issue 描述要解决的问题
2. 大改动前先讨论方案
3. 新功能 / Bug 修复尽量附带 JVM 单元测试（见 `app/src/test/java/...`）

## 许可证

MIT License —— 详见 [LICENSE](LICENSE) 文件（如未创建，请在使用时补充）。

## 致谢

- [AndroidX Media3](https://github.com/androidx/media) — 现代化播放引擎
- [OkHttp](https://square.github.io/okhttp/) — 可靠的网络栈
- [Alist](https://github.com/AlistGo/alist) — 通用的网盘聚合 WebDAV 网关

---

> LingTing 取名「零听」—— 零负担，听你想听。