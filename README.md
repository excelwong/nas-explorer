# NAS 文件管理器（nas-explorer）

安卓端 SMB / NAS 文件管理器，用 Kotlin 编写。它是 [nas-browser](https://github.com/excelwong/nas-browser) 的继续演进版本：包名 `com.example.nasexplorer` 与原版不同，**可以与原版同时安装**在一台手机上；Kotlin 命名空间仍是 `com.example.nasbrowser`（源码沿用）。

> 当前版本 **v4.1.14**（versionCode 55）。代码与文档中**不含任何 NAS 地址、账号或密码**，首次使用需在连接页手动填写。

---

## 功能

- **SMB 连接**：基于纯 Java 的 [`smbj`](https://github.com/hierynomus/smbj) 0.13.0，自动协商 SMB 3.1.1 → 3.0.2 → 3.0 → 2.1 → 2.0.2，**不协商已废弃的 SMB1**；另有 jcifs 兜底。连接参数用 `EncryptedSharedPreferences`（AndroidKeyStore 背书）加密保存在本机。
- **目录浏览**：文件夹置顶、按名/大小/时间排序，路径栏快速跳转；网格 / 列表视图切换，针对折叠屏与大屏做了列数自适应。
- **复制 / 移动 / 粘贴**：多选后通过 `FileClipboard` 跨目录复制或移动，`PasteUi` 显示单项与整体进度、剩余时间、可取消或转后台；遇到同名文件弹窗选择覆盖 / 跳过 / 全部应用。
- **下载与传输**：前台服务后台下载，通知栏常驻进度，传输列表可查看、取消、继续；支持断点续传与并发。
- **图片查看**：`ViewPager2` 翻页 + `ZoomImageView` 双指缩放 / 双击放大 / 拖动平移。
- **其它**：书签、局域网设备扫描、回收站、本机文件浏览、分栏详情面板、压缩包处理。

## 构建

- JDK 17、Android SDK（compileSdk 35 / minSdk 24 / targetSdk 35）、Gradle Wrapper。
- 插件通过根 `build.gradle` 的 `buildscript` classpath 引入（此构建环境下 Gradle Plugin Portal 不可达），仓库仅用 Google Maven + Maven Central。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

打 release 包需在仓库根目录放一份 `release-signing.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）。该文件与 `local.properties`、`.gradle/`、`app/build/`、`*.apk`、`*.aab`、`*.jks` 均已在 `.gitignore` 中排除，**不会入库**。

## 目录速览

| 路径 | 作用 |
|---|---|
| `app/src/main/java/com/example/nasbrowser/smb/` | SMB 连接、查询与写入（`SmbClientManager`、`SmbQueryOps`、`SmbWriteOps`） |
| `app/src/main/java/com/example/nasbrowser/viewmodel/` | 浏览页状态与业务逻辑 |
| `app/src/main/java/com/example/nasbrowser/util/` | 剪贴板模型与状态（`FileClipboard`、`PasteModels`） |
| `app/src/main/java/com/example/nasbrowser/PasteUi.kt` | 复制/移动进度与冲突处理对话框 |
| `app/src/main/java/com/example/nasbrowser/ZoomImageView.kt` | 可缩放图片视图 |
| `app/src/main/res/values/strings.xml` | 全部界面文案 |

## 相关项目

- [nas-browser](https://github.com/excelwong/nas-browser) — 初代版本，侧重多选下载与折叠屏适配。
