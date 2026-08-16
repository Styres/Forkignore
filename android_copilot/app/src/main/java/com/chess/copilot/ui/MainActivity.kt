package com.chess.copilot.ui

import android.app.Activity
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
import java.io.File

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

        // 展示上一次投影会话的落盘状态 (FloatingBubbleService 自取证)，辅助定位授权失效问题
        try {
            val stateFile = File(filesDir, "debug/projection_state.txt")
            if (stateFile.exists()) {
                tvResult.text = "【上次投影会话状态】\n${stateFile.readText()}"
            }
            // 展示上次实机建议的逐格取证 (bug_13/14 定案用): BoardRect/FEN/低置信格/门控截断候选，无需 adb 即可读取截图
            val diagFile = File(filesDir, "debug/last_diagnostic.txt")
            if (diagFile.exists()) {
                tvResult.append("\n【上次实机建议取证】\n${diagFile.readText()}")
            }
        } catch (_: Exception) {
        }
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
                val needRescue = (detailedResp is UltraRobustClassifier.ClassificationResponse.Success &&
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
                        if (rescueResp is UltraRobustClassifier.ClassificationResponse.Success &&
                            StockfishBridge.validateFenSanity(rescueResp.result.fullFen) == null
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
                        // 3. Stockfish 计算最佳走法
                        val eval = StockfishBridge.evaluateFen(res.fullFen)

                        val sb = StringBuilder()
                        sb.append("【离线诊断结果】\n")
                        sb.append("棋盘坐标: [L=${boardRect.left}, T=${boardRect.top}, R=${boardRect.right}, B=${boardRect.bottom}] 定位分=${String.format("%.0f", locateResult.score)}\n")
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
                        sb.append("-----------------------------\n")
                        val displayMove = when (eval.bestMove) {
                            "(checkmate)" -> "无合法走法 (胜负已分/将杀)"
                            "(stalemate)" -> "无合法走法 (和棋/逼和)"
                            "(none)" -> "无合法走法"
                            "(invalid)" -> "已拒绝: 识别出不可能局面 (非引擎故障，见下方诊断)"
                            else -> eval.bestMove
                        }
                        sb.append("推荐走法: $displayMove\n")
                        sb.append("局势评估分: ${if (eval.evalScore >= 0) "+" else ""}${String.format("%.2f", eval.evalScore)}\n")
                        sb.append("搜索深度: ${if (eval.depth <= 0) "0 层 ${if (eval.bestMove == "(invalid)") "[FEN预校验拦截]" else "[兜底生成器]"}" else "${eval.depth} 层"}\n")
                        if (eval.isMate) {
                            sb.append("杀棋状态: 胜势已锁定\n")
                        }
                        sb.append("-----------------------------\n")
                        sb.append("${StockfishBridge.lastDiagnosticInfo}\n")

                        tvResult.text = sb.toString()
                    }
                    is UltraRobustClassifier.ClassificationResponse.Rejected -> {
                        val sb = StringBuilder()
                        sb.append("【门禁拦截】\n")
                        sb.append("原因: ${detailedResp.reason}\n")
                        sb.append("取证看板: MedianSim=${String.format("%.3f", detailedResp.medianSim)} | 占位=${detailedResp.occupiedCount} | 定位分=${String.format("%.0f", locateResult.score)}\n")
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

    override fun onDestroy() {
        super.onDestroy()
        // 保持 Stockfish 引擎常驻供 FloatingBubbleService 持续调用，不在此处 release
    }
}
