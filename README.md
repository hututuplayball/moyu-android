<p align="center">
  <img src="artwork/moyu-logo-512.png" width="112" alt="墨屿" />
</p>

<h1 align="center">墨屿 Moyu</h1>

<p align="center">
  一个只想安静读书的本地阅读器。<br/>
  不联网、不登录、不推送，也不插入广告。
</p>

<p align="center">
  <img alt="minSdk" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12-4285F4?logo=jetpackcompose&logoColor=white" />
  <img alt="permissions" src="https://img.shields.io/badge/permissions-0-263E33" />
</p>

---

## 截图

> 待补充。把手机截图放进 `docs/screenshots/`，命名见该目录下的说明，然后取消下面这段的注释即可显示。

<!-- 截图就绪后取消注释
<p align="center">
  <img src="docs/screenshots/library.png" width="24%" alt="书架" />
  <img src="docs/screenshots/reader-comic.png" width="24%" alt="条漫阅读" />
  <img src="docs/screenshots/reader-text.png" width="24%" alt="文字阅读" />
  <img src="docs/screenshots/settings.png" width="24%" alt="设置" />
</p>
-->

---

## 功能

### 书架
- **漫画与电子书双书架**，页签随滑动切换，标题栏与搜索栏共用固定高度，展开搜索时书架不会跳动
- **分组管理**：按题材或状态分组，封面叠放展示；解散分组后书籍回到未分组，阅读进度保留
- **拖拽排序**：长按封面拖动，松手即保存
- **书名搜索**：支持一键清除，无结果时给出空状态提示
- **原文件失效可恢复**：文件被移动或删除后重新指定一次，认出同一本书，进度与书签继续保留

### 阅读
- **两种节奏**：上下连续适合条漫，左右翻页适合单页分镜，每本书可单独设置
- **缩放**：双指捏合 1×–5×，双击在 1× 与 2.5× 之间切换；放大后暂停翻页以降低内存占用
- **阅读页独立亮度**，离开阅读器即交还系统亮度
- **书签**：随手添加，调整字号后仍指向原来的位置
- **排版**：字号、行距、纸张主题统一设置，对全部电子书生效

### 性能
- 手指停止约 220 ms 后才开始预读，滑动与缩放过程中不预读
- 预读范围可调（前后各 1–3 页，默认 3 页）
- 位图缓存按设备内存自适应，退到后台即清空
- 仅保留可视区邻近页面的位图，其余立即释放

### 输入格式

| 格式 | 说明 |
| --- | --- |
| PDF | 原生 `PdfRenderer` 渲染，另用 PDFBox 提取文本层供排版与检索 |
| EPUB | 解析 OPF 元数据、封面与目录，兼容 EPUB 2（NCX）与 EPUB 3（导航文档） |
| TXT | 依次尝试 BOM、UTF-8、GBK 解码，可识别中文章节标题 |
| 图片漫画 | 整个文件夹导入，按自然序排列（「第 2 话」排在「第 10 话」前面） |

---

## 隐私

- **未申请任何权限**，`AndroidManifest.xml` 中没有任何 `<uses-permission>`，包括网络权限
- `android:allowBackup="false"`，系统级备份关闭
- 导入时优先引用原文件，只为书架显示复制一张封面
- 书架结构、阅读进度与书签仅保存在本机 Room 数据库中，可导出为备份文件自行保管
- 移出书架只清理封面、副本与阅读记录，**不会删除原文件**

---

## 技术栈

| 项目 | 版本 / 选型 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| 构建 | AGP 8.7.3 / Gradle Wrapper / JDK 17 |
| UI | Jetpack Compose（BOM 2024.12.01）+ Material 3 |
| 持久化 | Room 2.6.1、DataStore Preferences 1.1.1 |
| PDF 文本层 | PDFBox-Android 2.0.27.0 |
| 测试 | JUnit 4 + Robolectric 4.14.1 + Compose UI Test |
| SDK | minSdk 26（Android 8.0）/ compileSdk & targetSdk 35 |

---

## 构建

### 环境准备

1. JDK 17
2. Android SDK（compileSdk 35、build-tools 35.0.0）
3. 在仓库根目录创建 `local.properties`，指向本机 SDK：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

### 构建 Debug 包

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

### 构建 Release 包

Release 使用独立密钥签名，签名信息不在仓库内。在根目录创建 `keystore.properties`：

```properties
storeFile=../.tooling/your-release.jks
storePassword=******
keyAlias=******
keyPassword=******
```

然后：

```bash
gradlew.bat assembleRelease
```

产物位于 `app/build/outputs/apk/release/`。`keystore.properties`、`*.jks`、`*.keystore` 均已被 `.gitignore` 排除。

---

## 测试

```bash
# JVM 单元测试（Robolectric，覆盖解析、排序、排版、备份等）
gradlew.bat test

# 设备/模拟器上的界面测试
gradlew.bat connectedAndroidTest
```

---

## 项目结构

```
app/src/main/java/com/moyu/reader/
├── MainActivity.kt          入口 Activity
├── ReaderViewModel.kt       阅读器状态与生命周期
├── book/                    PDF / EPUB / TXT 解析、编码识别、文本排版与缓存
├── data/                    Room 实体与 DAO、仓库、偏好设置、备份编解码
├── page/                    页面源抽象（文本页 / 图片页）
├── pdf/                     PDF 会话封装
└── ui/                      Compose 界面：书架、阅读器、设置、主题、预读
```

```
artwork/                     品牌资源：logo 矢量图与位图
docs/screenshots/            界面截图
```

---

## 发版

```bash
git tag -a v1.0 -m "墨屿 1.0"
git push origin v1.0
```

在 GitHub 的 Release 页面选择该标签，上传 `app-release.apk` 后发布。

> Release 附件名只保留 ASCII 字符，中文会被剥离。建议将安装包命名为 `moyu-v1.0.apk`。

---

## 说明

- 卸载应用会清除本地数据，重装前请先在应用内导出备份
- 本仓库不包含任何签名文件与密钥
