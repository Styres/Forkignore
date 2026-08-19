# Android 本地开发与无线 ADB 调试规范

## 1. 系统与工具环境
- **JDK 与 SDK**：系统已永久配置 `JAVA_HOME` (`E:\Android\JDK\jdk-17`) 与 `ANDROID_HOME` (`E:\Android\Sdk`)。
- **命令行工具**：`adb` 与 `java` 已永久注册在系统 `Path` 中，支持全局直接调用。
- **Gradle 缓存**：`GRADLE_USER_HOME` (`E:\Android\.gradle`)。
- **APK 输出产物**：`d:\project\chess\android_copilot\app\build\outputs\apk\debug\app-debug.apk`

---

## 2. 本地编译与测试标准命令
- **运行单元测试**：
  ```powershell
  cd d:\project\chess\android_copilot; .\gradlew.bat testDebugUnitTest
  ```
- **编译 Debug APK**：
  ```powershell
  cd d:\project\chess\android_copilot; .\gradlew.bat assembleDebug
  ```

---

## 3. 真机无线调试（WiFi ADB）标准操作流程
- **手机局域网默认 IP**：`192.168.50.243`
- **目标固定端口**：`5555`

### 执行步骤：
1. **网络连通性探测（前置确认，严禁盲目连接）**：
   ```powershell
   Test-Connection -ComputerName 192.168.50.243 -Count 1 -Quiet
   ```
2. **连接与端口固化**：
   - **日常直连（默认 5555 端口）**：
     ```powershell
     adb connect 192.168.50.243:5555
     ```
   - **手机重启后重置固化（连接随机端口 `<port>` 后立即固化为 5555）**：
     ```powershell
     adb connect 192.168.50.243:<port>; adb -s 192.168.50.243:<port> tcpip 5555; adb connect 192.168.50.243:5555
     ```
3. **覆盖安装与拉起应用（仅在用户明确回复同意后执行）**：
   ```powershell
   adb -s 192.168.50.243:5555 install -r "d:\project\chess\android_copilot\app\build\outputs\apk\debug\app-debug.apk"; adb -s 192.168.50.243:5555 shell am start -n com.chess.copilot/.ui.MainActivity
   ```

---

## 4. AI 行为准则与交互门禁（核心规范）
1. **严格人工确认门禁（严禁自动推送与安装）**：
   - 当代码修改完成、单元测试通过并执行完 Git 提交后，**AI 必须停止自动操作，向用户主动询问提醒**：
     > “代码与测试已完成并提交，是否需要编译并通过 WiFi ADB 推送到真机安装实测？”
   - **在收到用户明确的确认指令前，绝对禁止擅自执行编译打包、ADB 连机或安装操作**。
2. **严格区分真机与云端**：
   - 用户指令中提及“推送/安装/真机实测”时，默认仅指通过本地 WiFi ADB 执行安装，**严禁误执行 `git push` 到远程仓库触发 GitHub Actions**（除非用户明确指明“推送 GitHub”）。
3. **连接异常排查机制**：
   - 若 Ping 失败或 ADB 5555 连接超时，主动提醒用户检查手机 Wi-Fi，或请用户提供开发者选项中最新的随机端口号。
