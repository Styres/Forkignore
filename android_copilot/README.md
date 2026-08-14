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
3. **100% 本地离线 Stockfish 引擎**：
   - 50~100ms 极速在手机端侧芯片计算出当前局面的 Best Move 与 Eval 评估分，零网络依赖。
4. **内置单步诊断面板**：
   - 打开 App 可直接从相册选图测试识别与引擎输出，拒绝“黑盒盲调”。

---

## 🚀 云端免配环境构建与使用指南

### 方案 A：GitHub Actions（最推荐，全自动打包生成 APK）
1. 将本项目推送到您的 GitHub 仓库；
2. GitHub Actions 会自动触发 [`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml) 构建流水线；
3. 构建完成后（约 1~2 分钟），在 GitHub 仓库的 **Actions** 页面进入最新的构建记录，在底部的 **Artifacts** 区域即可直接点击下载 `Duolingo-Chess-Copilot-APK`，解压后即可直接安装到手机！

### 方案 B：GitHub Codespaces（在浏览器网页里直接编辑与构建）
1. 在 GitHub 仓库页面点击 **Code** 按钮 -> 选择 **Codespaces** -> 点击 **Create codespace on master**；
2. 浏览器会自动打开一个完整的云端 VS Code 开发环境；
3. 在底部的网页终端中直接运行：
   ```bash
   cd android_copilot
   ./gradlew assembleDebug
   ```
4. 编译完成后，在左侧文件浏览器中找到 `app/build/outputs/apk/debug/app-debug.apk`，**右键选择 Download** 即可保存到本地！
