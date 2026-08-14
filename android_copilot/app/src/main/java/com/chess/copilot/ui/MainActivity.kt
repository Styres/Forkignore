package com.chess.copilot.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.chess.copilot.R
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.ChessOnnxEngine
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.service.FloatingBubbleService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvResult: TextView
    private var onnxEngine: ChessOnnxEngine? = null

    // 相册选择回调
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { runOfflineDiagnostic(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tvDiagnosticResult)
        onnxEngine = ChessOnnxEngine(this)

        findViewById<Button>(R.id.btnToggleFloating).setOnClickListener {
            checkOverlayPermissionAndStart()
        }

        findViewById<Button>(R.id.btnSelectImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限以在多邻国上方显示提示", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            val serviceIntent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "悬浮球已启动！请打开多邻国进行对战", Toast.LENGTH_SHORT).show()
            finish() // 最小化回到桌面
        }
    }

    private fun runOfflineDiagnostic(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    tvResult.text = "图片加载失败！"
                    return@launch
                }

                tvResult.text = "正在执行梳状滤波定位与 ONNX 推理..."

                // 1. 定位棋盘
                val boardRect = ChessLocator.locateBoard(bitmap)

                // 2. ONNX 识别
                val res = onnxEngine?.detect(bitmap, boardRect)
                if (res != null) {
                    // 3. Stockfish 计算
                    val eval = StockfishBridge.evaluateFen(res.fullFen)

                    val sb = StringBuilder()
                    sb.append("【诊断结果】\n")
                    sb.append("棋盘坐标: [L=${boardRect.left}, T=${boardRect.top}, R=${boardRect.right}, B=${boardRect.bottom}]\n")
                    sb.append("视角方向: ${if (res.isWhitePerspective) "执白 (White)" else "执黑 (Black)"}\n")
                    sb.append("局面 FEN: ${res.fen}\n")
                    sb.append("完整 FEN: ${res.fullFen}\n")
                    sb.append("-----------------------------\n")
                    sb.append("Stockfish 建议: ${eval.bestMove}\n")
                    sb.append("局势评估分: ${if (eval.evalScore >= 0) "+" else ""}${eval.evalScore}\n")
                    sb.append("搜索深度: ${eval.depth} 层\n")

                    tvResult.text = sb.toString()
                }
            } catch (e: Exception) {
                tvResult.text = "诊断报错: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        onnxEngine?.close()
    }
}
