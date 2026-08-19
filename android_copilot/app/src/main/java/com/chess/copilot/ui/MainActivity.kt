package com.chess.copilot.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chess.copilot.R
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.UltraRobustClassifier
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.service.FloatingBubbleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Rect
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 主控制台与诊断界面
 * 支持 Android 14 规范的 MediaProjection 授权申请与本地截图离线诊断
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvResult: TextView
    private var classifier: UltraRobustClassifier? = null

    // 相册选图回调（离线诊断）
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { runOfflineDiagnostic(it) }
    }

    // Android 14 屏幕录制/截屏授权回调
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startBubbleServiceWithProjection(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "未获得截屏授权，悬浮助手未启动", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tvDiagnosticResult)
        classifier = UltraRobustClassifier(this)
        StockfishBridge.init(this)

        findViewById<Button>(R.id.btnToggleFloating).setOnClickListener {
            checkOverlayPermissionAndRequestCapture()
        }

        findViewById<Button>(R.id.btnSelectImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // 展示上一次投影会话与诊断落盘状态 (自取证)，标明历史记录并格式化时间
        try {
            val sb = StringBuilder()
            val stateFile = File(filesDir, "debug/projection_state.txt")
            if (stateFile.exists() && stateFile.length() > 0) {
                sb.append("【历史记录 - 上次投影会话状态】\n${formatHistoricalLog(stateFile.readText())}\n\n")
            }
            // 展示上次实机/离线诊断的逐格取证 (bug_13/14 定案用)
            val diagFile = File(filesDir, "debug/last_diagnostic.txt")
            if (diagFile.exists() && diagFile.length() > 0) {
                sb.append("【历史记录 - 最近诊断取证】\n${formatHistoricalLog(diagFile.readText())}")
            }
            if (sb.isNotEmpty()) {
                tvResult.text = sb.toString().trimEnd()
            }
        } catch (_: Exception) {
        }
    }

    private fun formatHistoricalLog(rawText: String): String {
        val timePattern = Pattern.compile("Time:\\s*(\\d{10,13})")
        val matcher = timePattern.matcher(rawText)
        val sb = StringBuffer()
        while (matcher.find()) {
            val millis = matcher.group(1)?.toLongOrNull()
            if (millis != null) {
                val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
                matcher.appendReplacement(sb, "记录时间: $formatted")
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun checkOverlayPermissionAndRequestCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限以在多邻国上方显示走法", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 申请 Android 14 MediaProjection 截屏授权
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startBubbleServiceWithProjection(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(FloatingBubbleService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingBubbleService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "悬浮球已启动！请打开多邻国进行对战", Toast.LENGTH_SHORT).show()
        finish() // 最小化回到桌面
    }

    private fun runOfflineDiagnostic(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                tvResult.text = "正在读取图片并执行棋盘识别与算招..."
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    tvResult.text = "图片加载失败！"
                    return@launch
                }

                // 1. 梳状滤波两阶段定位棋盘 (带置信分, bug_18 遥测用)
                var locateResult = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(bitmap)
                }
                var boardRect = locateResult.rect

                // 2. 带取证看板的详尽决策管道 (MedianSim / 占位 / 拦截原因)
                var detailedResp = withContext(Dispatchers.Default) {
                    classifier?.classifyBoardDetailed(bitmap, boardRect)
                }

                // 2.5 候选救援 (bug_19/superbug 定案): 主框产出"不可能局面"或整体被门禁拦截，都是定位器双峰误选
                // 假框的实锤特征 (假框因 UI 边缘能量可反超真框 710 vs 670)，自动改用次候选框重识别
                // 【修改】同时要求定位置信度不能为 low，残差极小
                val needRescue = locateResult.confidence == "low" ||
                    (detailedResp is UltraRobustClassifier.ClassificationResponse.Success &&
                    StockfishBridge.validateFenSanity(detailedResp.result.fullFen) != null) ||
                    detailedResp is UltraRobustClassifier.ClassificationResponse.Rejected
                    
                if (needRescue) {
                    val candidates = withContext(Dispatchers.Default) {
                        ChessLocator.locateTopCandidates(bitmap, 2)
                    }
                    if (candidates.size >= 2) {
                        val rescueRect = candidates[1].rect
                        val rescueResp = withContext(Dispatchers.Default) {
                            classifier?.classifyBoardDetailed(bitmap, rescueRect)
                        }
                        // 【修改】强制约束：次候选残差必须满足相对格宽比例 (<= 5%)
                        val rescueMaxResid = (rescueRect.width() / 8.0f) * 0.05f
                        if (rescueResp is UltraRobustClassifier.ClassificationResponse.Success &&
                            StockfishBridge.validateFenSanity(rescueResp.result.fullFen) == null &&
                            candidates[1].residual <= rescueMaxResid && 
                            candidates[1].confidence != "low" 
                        ) {
                            locateResult = candidates[1]
                            boardRect = rescueRect
                            detailedResp = rescueResp
                        }
                    }
                }

                when (detailedResp) {
                    is UltraRobustClassifier.ClassificationResponse.Success -> {
                        val res = detailedResp.result
                        
                        // 【新增】离线诊断也将 FEN 自动复制到剪贴板
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("FEN", res.fullFen)
                        clipboard.setPrimaryClip(clip)
                        
                        // 3. Stockfish 计算最佳走法（返回封装有该次计算专属诊断信息的 EngineEvaluation）
                        val eval = StockfishBridge.evaluateFen(res.fullFen)

                        val sb = StringBuilder()
                        sb.append("【离线诊断结果】\n")
                        val cropTag = if (locateResult.isCropped) " [裁剪帧]" else ""
                        sb.append("棋盘坐标: [L=${boardRect.left}, T=${boardRect.top}, R=${boardRect.right}, B=${boardRect.bottom}] 定位分=${String.format("%.0f", locateResult.score)} | 置信度=${locateResult.confidence} | 残差=${String.format("%.2f", locateResult.residual)}px$cropTag\n")
                        sb.append("视角方向: ${if (res.isWhitePerspective) "执白 (White)" else "执黑 (Black)"}\n")
                        sb.append("取证看板: MedianSim=${String.format("%.3f", detailedResp.medianSim)} | 占位=${detailedResp.occupiedCount}\n")
                        // 逐格取证 (bug_11~14 定案用): 低置信格 = 误分类嫌疑; 门控截断候选 = 漏子嫌疑 (std=中心方差 grad=边缘梯度)
                        if (detailedResp.lowConfidenceCells.isNotEmpty()) {
                            sb.append("低置信格: ${detailedResp.lowConfidenceCells.joinToString(" ")}\n")
                        }
                        if (detailedResp.gateRejectedCells.isNotEmpty()) {
                            sb.append("门控截断候选: ${detailedResp.gateRejectedCells.joinToString(" ")}\n")
                        }
                        sb.append("局面 FEN: ${res.boardFen}\n")
                        sb.append("完整 FEN: ${res.fullFen}\n")
                        sb.append("（✅ FEN 已自动复制到剪贴板）\n")
                        sb.append("-----------------------------\n")
                        
                        // 【新增】拼接带有棋子类型的走法字符串
                        var displayMoveStr = eval.bestMove
                        if (eval.bestMove.length >= 4 && eval.bestMove[0] in 'a'..'h') {
                            val fileIdx = eval.bestMove[0] - 'a'
                            val rankIdx = 8 - (eval.bestMove[1] - '0')
                            val pieceChar = res.standardBoard[rankIdx][fileIdx]
                            if (!pieceChar.equals('p', ignoreCase = true) && pieceChar != '.') {
                                displayMoveStr = "${pieceChar.uppercaseChar()}-${eval.bestMove}"
                            }
                        }
                        
                        val displayMove = when (eval.bestMove) {
                            "(checkmate)" -> "无合法走法 (胜负已分/将杀)"
                            "(stalemate)" -> "无合法走法 (和棋/逼和)"
                            "(none)" -> "无合法走法"
                            "(invalid)" -> "已拒绝: 识别出不可能局面 (非引擎故障，见下方诊断)"
                            else -> displayMoveStr
                        }
                        sb.append("推荐走法: $displayMove\n")
                        sb.append("局势评估分: ${if (eval.evalScore >= 0) "+" else ""}${String.format("%.2f", eval.evalScore)}\n")
                        sb.append("搜索深度: ${if (eval.depth <= 0) "0 层 ${if (eval.bestMove == "(invalid)") "[FEN预校验拦截]" else "[兜底生成器]"}" else "${eval.depth} 层"}\n")
                        if (eval.isMate) {
                            sb.append("杀棋状态: 胜势已锁定\n")
                        }
                        sb.append("-----------------------------\n")
                        sb.append("${eval.diagnosticInfo}\n")

                        tvResult.text = sb.toString()
                        Toast.makeText(this@MainActivity, "FEN 已复制到剪贴板", Toast.LENGTH_SHORT).show()

                        // 同步持久化诊断日志，供下次启动展示最近一次诊断
                        saveOfflineDiagnosticArtifact(boardRect, locateResult, res.fullFen, detailedResp, eval)
                    }
                    is UltraRobustClassifier.ClassificationResponse.Rejected -> {
                        val sb = StringBuilder()
                        sb.append("【门禁拦截】\n")
                        sb.append("原因: ${detailedResp.reason}\n")
                        val cropTag = if (locateResult.isCropped) " [裁剪帧]" else ""
                        sb.append("取证看板: MedianSim=${String.format("%.3f", detailedResp.medianSim)} | 占位=${detailedResp.occupiedCount} | 定位分=${String.format("%.0f", locateResult.score)} | 置信度=${locateResult.confidence} | 残差=${String.format("%.2f", locateResult.residual)}px$cropTag\n")
                        sb.append("棋盘坐标: [L=${boardRect.left}, T=${boardRect.top}, R=${boardRect.right}, B=${boardRect.bottom}]\n")
                        if (detailedResp.lowConfidenceCells.isNotEmpty()) {
                            sb.append("低置信格: ${detailedResp.lowConfidenceCells.joinToString(" ")}\n")
                        }
                        if (detailedResp.gateRejectedCells.isNotEmpty()) {
                            sb.append("门控截断候选: ${detailedResp.gateRejectedCells.joinToString(" ")}\n")
                        }
                        tvResult.text = sb.toString()
                    }
                    null -> {
                        tvResult.text = "分类器未初始化"
                    }
                }
            } catch (e: Exception) {
                tvResult.text = "诊断报错: ${e.message}"
            }
        }
    }

    private fun saveOfflineDiagnosticArtifact(
        boardRect: Rect,
        locateResult: ChessLocator.LocateResult,
        fen: String,
        detailedResp: UltraRobustClassifier.ClassificationResponse.Success,
        eval: StockfishBridge.EngineEvaluation
    ) {
        try {
            val debugDir = File(filesDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            val txtFile = File(debugDir, "last_diagnostic.txt")
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val lowConf = if (detailedResp.lowConfidenceCells.isNotEmpty()) "LowConf: ${detailedResp.lowConfidenceCells.joinToString(" ")}\n" else ""
            val gate = if (detailedResp.gateRejectedCells.isNotEmpty()) "GateRejected: ${detailedResp.gateRejectedCells.joinToString(" ")}\n" else ""
            txtFile.writeText(
                "来源: [离线单步诊断]\n" +
                "BoardRect: $boardRect\n" +
                "LocateScore: ${String.format("%.1f", locateResult.score)}\n" +
                "Confidence: ${locateResult.confidence}\n" +
                "Residual: ${String.format("%.2f", locateResult.residual)}\n" +
                "IsCropped: ${locateResult.isCropped}\n" +
                "FEN: $fen\n" +
                "Move: ${eval.bestMove} (Score: ${eval.evalScore}, Depth: ${eval.depth})\n" +
                "$lowConf$gate" +
                "记录时间: $timeStr\n"
            )
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // 保持 Stockfish 引擎常驻供 FloatingBubbleService 持续调用，不在此处 release
    }
}
