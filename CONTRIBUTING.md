# 🤝 贡献指南 (Contributing Guide)

感谢您对 **Duolingo Chess Copilot** 的关注！我们非常欢迎来自社区的代码贡献、Bug 修复、算法改进和功能建议。

---

## 🛠️ 开发与本地测试

### 环境要求
1. **JDK 17** (推荐 Temurin JDK 17)
2. **Android Studio** (推荐 Hedgehog / Iguana / Ladybug 或更高版本)
3. **Android NDK** (用于编译 Stockfish C++ 源码，如需二次开发引擎)
4. **Git LFS** (克隆仓库后请运行 `git lfs pull`，以获取真实的 NNUE 权重)

### 提交前本地自测
在提交 Pull Request 前，请确保所有 Kotlin 单元测试均已通过：

```bash
cd android_copilot
./gradlew testDebugUnitTest
```

---

## 📝 提交规范 (Commit & PR)

1. **分支管理**：
   - 请基于 `master` 分支创建新的功能/修复分支：`git checkout -b feature/your-feature-name` 或 `fix/your-fix-name`。
2. **Commit 格式**：
   - 推荐使用 Conventional Commits 规范，例如：
     - `feat: 添加新型棋盘边缘检测算法`
     - `fix: 修复特定分辨率下悬浮窗尺寸计算偏差`
     - `docs: 完善英文 README 说明`
     - `test: 增加残局双王位置单元测试用例`
3. **代码风格**：
   - 遵循 Kotlin 官方代码风格指南，禁止使用隐式非受检转换；
   - 新增复杂算法或数学计算逻辑需配备对应的单元测试。

---

## 🐛 报告问题 (Bug Reports)

若在多邻国特定关卡或特定机型上遇到定位偏差或识别错误，欢迎提交 Issue。提交时请附带：
- 手机型号与 Android 系统版本；
- 出现异常时的屏幕截图（请存入 `test_images/bugs/` 进行复现）；
- 复现步骤与期望结果。
