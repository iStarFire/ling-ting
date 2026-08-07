# 零听 (LingTing)

极简 Android 听书 App，通过 Alist WebDAV 连接网盘，在线播放听书资源。

## 功能

- 配置 WebDAV 服务器（Alist）
- 浏览网盘目录，选择音频文件
- 书架管理
- 在线播放（不下载到本地）
- 播放进度自动记忆续播
- 后台播放 + 通知栏控制
- 睡眠定时器

## 构建方式

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- Media3 (ExoPlayer)
- Room (本地数据库)
- Hilt (依赖注入)
- OkHttp (WebDAV)
