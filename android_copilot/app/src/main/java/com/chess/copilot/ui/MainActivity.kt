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

                // 1. 梳状滤波两阶段定位棋盘
                val boardRect = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(bitmap)
                }

                // 2. 纯 Kotlin 梯度场 NCC + 2-Means 识别
                val res = withContext(Dispatchers.Default) {
                    classifier?.classifyBoard(bitmap, boardRect)
                }

                if (res != null) {
                    // 3. Stockfish 计算最佳走法
                    val eval = StockfishBridge.evaluateFen(res.fullFen)

                    val sb = StringBuilder()
                    sb.append("【离线诊断结果】\n")
                    sb.append("棋盘坐标: [L=${boardRect.left}, T=${boardRect.top}, R=${boardRect.right}, B=${boardRect.bottom}]\n")
                    sb.append("视角方向: ${if (res.isWhitePerspective) "执白 (White)" else "执黑 (Black)"}\n")
                    sb.append("局面 FEN: ${res.boardFen}\n")
                    sb.append("完整 FEN: ${res.fullFen}\n")
                    sb.append("-----------------------------\n")
                    sb.append("Stockfish 建议: ${eval.bestMove}\n")
                    sb.append("局势评估分: ${if (eval.evalScore >= 0) "+" else ""}${String.format("%.2f", eval.evalScore)}\n")
                    sb.append("搜索深度: ${eval.depth} 层\n")
                    if (eval.isMate) {
                        sb.append("杀棋状态: 胜势已锁定\n")
                    }

                    tvResult.text = sb.toString()
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
