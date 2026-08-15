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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.UltraRobustClassifier
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.ui.MainActivity
import com.chess.copilot.ui.TransparentCanvasOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 屏幕边缘悬浮球服务 (Floating Bubble)
 * 符合 Android 14 规范的前台服务
 * 核心机制：
 * 1. 消除 Overlay 自污染：截帧前瞬间隐藏悬浮球与透明图层，捕获纯净屏幕后恢复
 * 2. 常驻长连接 VirtualDisplay + 持续消费丢弃过渡帧，彻底消除单次点击注销会话与首帧残留
 * 3. 跨机型通用 pixelStride 像素拷贝
 * 4. 真机落盘诊断功能 (filesDir/debug/)
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
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420
    private var isAnalyzing = false
    @Volatile
    private var isCapturingFrame = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        transparentOverlay = TransparentCanvasOverlay(this)
        classifier = UltraRobustClassifier(this)
        StockfishBridge.init(this)

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        updateScreenMetrics()
        createFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Android 14 严格要求：必须先调用 startForeground 指定 mediaProjection 类型
        startForegroundNotification()

        // 2. 提取截屏授权 Token 并初始化常驻 VirtualDisplay 会话
        if (intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

            if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
                setupMediaProjection(resultCode, resultData)
            }
        }

        return START_NOT_STICKY
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
            cleanupProjection()
            updateScreenMetrics()

            val proj = mediaProjectionManager?.getMediaProjection(resultCode, data)
            mediaProjection = proj
            proj?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanupProjection()
                }
            }, captureHandler)

            val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            // 挂载后台长驻监听，持续消费并丢弃过渡帧，确保缓冲区不堆积
            reader.setOnImageAvailableListener({ ir ->
                if (!isCapturingFrame) {
                    try {
                        val img = ir.acquireLatestImage()
                        img?.close()
                    } catch (_: Exception) {}
                }
            }, captureHandler)

            virtualDisplay = proj?.createVirtualDisplay(
                "ChessPersistentDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
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

        if (mediaProjection == null || imageReader == null || virtualDisplay == null) {
            Toast.makeText(this, "截屏授权已失效，请重新开启悬浮助手", Toast.LENGTH_LONG).show()
            val reAuthIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(reAuthIntent)
            return
        }

        isAnalyzing = true

        serviceScope.launch {
            var screenBitmap: Bitmap? = null
            try {
                // 1. 消除 Overlay 自污染：截帧前隐藏悬浮球与透明走法图层
                transparentOverlay?.hide()
                bubbleView?.visibility = View.INVISIBLE
                delay(45) // 等待 45ms 确保屏幕完成无图层合成

                // 2. 从常驻 ImageReader 中获取最新纯净鲜活帧
                screenBitmap = captureCurrentScreenBitmap()

                // 3. 恢复悬浮球显示
                bubbleView?.visibility = View.VISIBLE

                if (screenBitmap == null) {
                    Toast.makeText(this@FloatingBubbleService, "画面截取中，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 4. SAT 棋盘定位
                val boardRect = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(screenBitmap)
                }

                // 5. 梯度场 NCC + 语义匹配质量门禁
                val res = withContext(Dispatchers.Default) {
                    classifier?.classifyBoard(screenBitmap, boardRect)
                }

                val copyForDebug = try {
                    screenBitmap.copy(screenBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) { null }

                if (res != null) {
                    // 6. 异步保存真机落盘诊断图与 FEN
                    saveDebugArtifactsAsync(copyForDebug, boardRect, res.fullFen)

                    // 7. Stockfish 引擎高速算招 (带缓存与自愈)
                    val eval = StockfishBridge.evaluateFen(res.fullFen, moveTimeMs = 120)

                    // 8. 在全透明 Canvas 上绘制走法箭头与局势胶囊
                    transparentOverlay?.showSuggestion(res.boardRect, eval, res.isWhitePerspective)
                } else {
                    // 语义门禁拦截（非棋盘画面或置信度不足）：清空图层并保存落盘记录
                    transparentOverlay?.hide()
                    saveDebugArtifactsAsync(copyForDebug, boardRect, "NO_BOARD_DETECTED")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingBubbleService, "未检测到有效棋盘画面", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                bubbleView?.visibility = View.VISIBLE
                screenBitmap?.recycle()
                isAnalyzing = false
            }
        }
    }

    /**
     * 从常驻 ImageReader 中提取当前最新鲜一帧 Bitmap
     */
    private suspend fun captureCurrentScreenBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        isCapturingFrame = true
        try {
            var bmp: Bitmap? = null
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 250L) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        bmp = if (pixelStride == 4 && rowPadding == 0) {
                            val b = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
                            b.copyPixelsFromBuffer(buffer)
                            b
                        } else {
                            val paddedW = screenWidth + rowPadding / pixelStride
                            val temp = Bitmap.createBitmap(paddedW, screenHeight, Bitmap.Config.ARGB_8888)
                            temp.copyPixelsFromBuffer(buffer)
                            val cropped = Bitmap.createBitmap(temp, 0, 0, screenWidth, screenHeight)
                            temp.recycle()
                            cropped
                        }
                    } finally {
                        image.close()
                    }
                    break
                } else {
                    delay(10)
                }
            }
            return@withContext bmp
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            isCapturingFrame = false
        }
    }

    private fun saveDebugArtifactsAsync(bitmap: Bitmap?, rect: android.graphics.Rect, fen: String) {
        val b = bitmap ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val debugDir = File(filesDir, "debug")
                if (!debugDir.exists()) debugDir.mkdirs()

                val imgFile = File(debugDir, "last_capture.png")
                val fos = FileOutputStream(imgFile)
                b.compress(Bitmap.CompressFormat.PNG, 90, fos)
                fos.flush()
                fos.close()

                val txtFile = File(debugDir, "last_diagnostic.txt")
                txtFile.writeText("BoardRect: $rect\nFEN: $fen\nTime: ${System.currentTimeMillis()}\n")
            } catch (_: Exception) {
            } finally {
                b.recycle()
            }
        }
    }

    private fun cleanupProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        try {
            imageReader?.close()
        } catch (_: Exception) {}
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        bubbleView?.let { windowManager.removeView(it) }
        transparentOverlay?.hide()
        cleanupProjection()
        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        StockfishBridge.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
