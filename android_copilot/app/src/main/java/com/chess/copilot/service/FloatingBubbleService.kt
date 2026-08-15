package com.chess.copilot.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.UltraRobustClassifier
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.ui.TransparentCanvasOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 屏幕边缘悬浮球服务 (Floating Bubble)
 * 符合 Android 14 规范的前台服务 + MediaProjection 截屏 -> 梯度场识别 -> Stockfish 算招 -> 透明绘制
 */
class FloatingBubbleService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "chess_copilot_service"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var transparentOverlay: TransparentCanvasOverlay? = null
    private var classifier: UltraRobustClassifier? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420
    private var isAnalyzing = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        transparentOverlay = TransparentCanvasOverlay(this)
        classifier = UltraRobustClassifier(this)
        StockfishBridge.init(this)

        updateScreenMetrics()
        createFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Android 14 严格要求：必须先调用 startForeground 指定 mediaProjection 类型
        startForegroundNotification()

        // 2. 提取截屏授权 Token 并初始化投影
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            setupMediaProjection(resultCode, resultData)
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Duolingo Chess Copilot",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Duolingo Chess Copilot")
            .setContentText("悬浮助手正在运行，轻点屏幕悬浮球即可出招")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanupProjection()
                }
            }, null)

            // 创建 ImageReader 与 VirtualDisplay
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ChessScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "截屏初始化异常: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createFloatingBubble() {
        bubbleView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.argb(220, 30, 180, 80))
            setPadding(20, 20, 20, 20)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            135,
            135,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 500
        }

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
        if (isAnalyzing) return
        isAnalyzing = true

        serviceScope.launch {
            try {
                // 1. 截取当前屏幕一帧 Bitmap
                val screenBitmap = captureScreenBitmap()
                if (screenBitmap == null) {
                    Toast.makeText(this@FloatingBubbleService, "正在捕获画面，请稍后重试", Toast.LENGTH_SHORT).show()
                    isAnalyzing = false
                    return@launch
                }

                // 2. 梳状谐振滤波定位棋盘
                val boardRect = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(screenBitmap)
                }

                // 3. 梯度场 NCC + 2-Means 识别棋子矩阵与 FEN
                val res = withContext(Dispatchers.Default) {
                    classifier?.classifyBoard(screenBitmap, boardRect)
                }

                if (res != null) {
                    // 4. Stockfish 引擎高速算招
                    val eval = StockfishBridge.evaluateFen(res.fullFen, moveTimeMs = 120)

                    // 5. 在全透明 Canvas 上绘制走法箭头与局势胶囊
                    transparentOverlay?.showSuggestion(res.boardRect, eval, res.isWhitePerspective)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isAnalyzing = false
            }
        }
    }

    private suspend fun captureScreenBitmap(): Bitmap? = withContext(Dispatchers.Default) {
        val reader = imageReader ?: return@withContext null
        val image = reader.acquireLatestImage() ?: return@withContext null

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            return@withContext Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            image.close()
        }
    }

    private fun cleanupProjection() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection = null
            virtualDisplay = null
            imageReader = null
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        transparentOverlay?.hide()
        cleanupProjection()
        StockfishBridge.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
