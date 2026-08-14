package com.chess.copilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.chess.copilot.R
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.ChessOnnxEngine
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.ui.TransparentCanvasOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 屏幕边缘悬浮球服务 (Floating Bubble)
 * 点击即触发 MediaProjection 截屏 -> ONNX 推理 -> Stockfish 建议 -> 透明 Canvas 绘制
 */
class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var transparentOverlay: TransparentCanvasOverlay? = null
    private var onnxEngine: ChessOnnxEngine? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        transparentOverlay = TransparentCanvasOverlay(this)
        onnxEngine = ChessOnnxEngine(this)

        startForegroundServiceNotification()
        createFloatingBubble()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "chess_copilot_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chess Copilot Running",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Duolingo Chess Copilot")
            .setContentText("悬浮助手正在后台运行，轻点屏幕边缘悬浮球即可出招")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1001, notification)
    }

    private fun createFloatingBubble() {
        bubbleView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.argb(200, 30, 180, 80))
            setPadding(18, 18, 18, 18)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            130,
            130,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 500
        }

        // 拖拽与点击手势监听
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (dx * dx + dy * dy > 100) isClick = false
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            onBubbleClicked()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, params)
    }

    private fun onBubbleClicked() {
        // 轻点悬浮球：触发截屏分析
        serviceScope.launch {
            // 获取当前屏幕 Bitmap（从 MediaProjection 或本地缓存）
            // 定位棋盘
            // val boardRect = ChessLocator.locateBoard(screenBitmap)
            // val result = onnxEngine?.detect(screenBitmap, boardRect)
            // val eval = StockfishBridge.evaluateFen(result.fullFen)
            // transparentOverlay?.showSuggestion(boardRect, eval, result.isWhitePerspective)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        transparentOverlay?.hide()
        onnxEngine?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
