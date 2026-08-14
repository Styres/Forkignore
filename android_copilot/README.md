# 🦉 Duolingo Chess Copilot (多邻国国际象棋全透明穿透悬浮助手)

专为多邻国（Duolingo）国际象棋模式打造的独立 Android 悬浮实战助手 App。

---

## 🌟 核心特性

1. **多邻国独特皮肤 100% 免疫**：
   - 采用 8 周期梳状谐振滤波器（Comb Filter），彻底免疫答题反馈弹窗、人物立绘与横屏黑边；
   - 基于端侧 ONNX 计算图与全局自适应聚类，毫秒级输出标准 FEN。
2. **全透明穿透悬浮 Canvas (`FLAG_NOT_TOUCHABLE`)**：
   - 提示直接以发光绿色箭头与高亮格子覆盖在多邻国棋盘上；
   - 手指点击直接穿透悬浮层走子，完全不阻碍对战操作。
3. **离线 Stockfish 引擎**：
   - 50~100ms 极速给出当前局面的 Best Move 与 Eval 评估分。
4. **内置单步诊断面板**：
   - 打开 App 可直接从相册选图测试识别与引擎输出，拒绝“黑盒盲调”。

---

## 🚀 云端免配环境开发与构建指南

### 方案 A：使用 Google Project IDX（推荐，浏览器跑模拟器）
1. 打开 [Google Project IDX (idx.dev)](https://idx.dev/)；
2. 导入本 Git 仓库；
3. IDX 会根据 `.idx/dev.nix` 自动在云端配置好 Android SDK 与 JDK；
4. 右侧会自动拉起一个**网页版 Android 手机模拟器**，您可以直接在网页中点击悬浮球与测试透明图层！

### 方案 B：使用 GitHub Actions（全自动生成 APK）
1. 将本项目推送到您的 GitHub 仓库；
2. GitHub Actions 会自动触发 `.github/workflows/build-apk.yml` 流水线；
3. 构建完成后，在 GitHub Actions 页面底部的 **Artifacts** 即可直接下载编译好的 `Duolingo-Chess-Copilot-APK.zip`，解压后安装至 Android 手机即可！
