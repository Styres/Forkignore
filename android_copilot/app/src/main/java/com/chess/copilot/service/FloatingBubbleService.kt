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
    @Volatile
    private var sessionLockedPerspective: Boolean? = null

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
        startForegroundNotification()

        if (intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

            if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
                setupMediaProjection(resultCode, resultData)
            } else if (mediaProjection == null) {
                // 响亮失败：授权数据异常必须现场可见并落盘，杜绝静默跳过导致的"气泡假活"
                val reason = "授权 Intent 异常 (resultCode=$resultCode, dataPresent=${resultData != null})"
                recordProjectionState("初始化失败: $reason")
                Toast.makeText(this, "截屏授权数据异常: $reason，请点击悬浮球重新授权", Toast.LENGTH_LONG).show()
            }
        } else {
            recordProjectionState("初始化失败: 服务被系统重启 (intent=null)，Token 已失效")
            Toast.makeText(this, "服务被系统重启，截屏授权已失效，请点击悬浮球重新授权", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "多邻国国象助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕悬浮辅助服务"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("多邻国国际象棋助手正在运行")
            .setContentText("点击屏幕悬浮球即可获取 Stockfish 实时最佳走法")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                recordProjectionState("初始化失败: getMediaProjection 返回 null (Token 无效或已被消费)")
                Toast.makeText(this, "MediaProjection 初始化失败", Toast.LENGTH_SHORT).show()
                return
            }

            // 会话生命周期监听：系统终止共享时立即回收引用并大声告知，避免僵尸态 (引用非 null 但会话已死)
            mediaProjection?.registerCallback(projectionCallback, captureHandler)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ChessScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                captureHandler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturingFrame) {
                    val img = reader.acquireLatestImage()
                    img?.close()
                }
            }, captureHandler)

            recordProjectionState("投影会话建立成功 (${screenWidth}x${screenHeight}, dpi=$screenDensity)")
            Toast.makeText(this, "悬浮助手已就绪", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // 半初始化回滚：任何一步失败都清掉已创建的资源，保证三引用状态一致，杜绝脏状态
            recordProjectionState("投影会话启动异常: ${e.javaClass.simpleName}: ${e.message}")
            rollbackProjection()
            Toast.makeText(this, "启动截屏会话异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            recordProjectionState("系统终止了屏幕共享会话 (MediaProjection.Callback#onStop)")
            rollbackProjection()
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    this@FloatingBubbleService,
                    "屏幕共享会话已被系统终止，请点击悬浮球重新授权",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 回滚/回收投影资源但不主动 stop() (onStop 回调中会话已由系统终止)
     */
    private fun rollbackProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        try {
            imageReader?.close()
        } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    /**
     * 投影会话状态落盘自取证 (filesDir/debug/projection_state.txt)，供 MainActivity 诊断页展示
     */
    private fun recordProjectionState(desc: String) {
        try {
            val debugDir = File(filesDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            File(debugDir, "projection_state.txt").writeText("$desc\nTime: ${System.currentTimeMillis()}\n")
        } catch (_: Exception) {
        }
    }

    private fun createFloatingBubble() {
        bubbleView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.argb(210, 88, 204, 2))
            setPadding(20, 20, 20, 20)
            elevation = 25f
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 180
            y = screenHeight / 3
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                        if (dx * dx + dy * dy > 25) {
                            isClick = false
                        }
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
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
                transparentOverlay?.hide()
                bubbleView?.visibility = View.INVISIBLE
                // 150ms 而非 60ms (bug_13/14 嫌疑): VirtualDisplay 从窗口移除到干净帧入缓冲区存在合成延迟，
                // 等待不足时截到的仍是带建议黑框/箭头的旧帧，第 8 横线被盖住即产生"阻隔子漏检"假象
                delay(150)

                screenBitmap = captureCurrentScreenBitmap()

                bubbleView?.visibility = View.VISIBLE

                if (screenBitmap == null) {
                    Toast.makeText(this@FloatingBubbleService, "画面截取中，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val locateResult = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(screenBitmap)
                }
                val boardRect = locateResult.rect

                val detailedResp = withContext(Dispatchers.Default) {
                    classifier?.classifyBoardDetailed(
                        bitmap = screenBitmap,
                        boardRect = boardRect,
                        overridePerspective = sessionLockedPerspective
                    )
                }

                val copyForDebug = try {
                    screenBitmap.copy(screenBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) { null }

                when (detailedResp) {
                    is UltraRobustClassifier.ClassificationResponse.Success -> {
                        val res = detailedResp.result
                        // 方案 A: 使用纯函数状态机更新会话锁定视角 (新局>=26颗子强制校准为 detectedPerspective, 中残局锁定不漂移)
                        sessionLockedPerspective = UltraRobustClassifier.resolvePerspectiveLock(
                            currentLock = sessionLockedPerspective,
                            detectedPerspective = detailedResp.detectedPerspective,
                            occupiedCount = detailedResp.occupiedCount,
                            medianSim = detailedResp.medianSim
                        )

                        val conflictDesc = if (sessionLockedPerspective != null && detailedResp.detectedPerspective != res.isWhitePerspective) {
                            ", 探测:${if (detailedResp.detectedPerspective) "白" else "黑"}"
                        } else ""
                        val lockedStateDesc = if (sessionLockedPerspective != null) "(锁定$conflictDesc)" else ""
                        val perspectiveName = if (res.isWhitePerspective) "执白$lockedStateDesc" else "执黑$lockedStateDesc"
                        
                        // 逐格取证落盘 (bug_11~14 定案用): 低置信格与门控截断候选写入 cells_forensics.txt
                        // LocateScore (bug_18 定案用): 定位器置信分，真棋盘实测 649~1505，待真机失败帧标定硬门禁阈值
                        val cellForensics = buildString {
                            appendLine("LocateScore: ${String.format("%.1f", locateResult.score)}")
                            appendLine("LowConf: ${detailedResp.lowConfidenceCells.joinToString(" ")}")
                            appendLine("GateRejected: ${detailedResp.gateRejectedCells.joinToString(" ")}")
                        }
                        saveDebugArtifactsAsync(copyForDebug, boardRect, res.fullFen, cellForensics)
                        
                        val eval = StockfishBridge.evaluateFen(res.fullFen, moveTimeMs = 200)
                        
                        // 兜底大声告知 (bug_15 教训): 悬浮层出现 [兜] 时现场即给出引擎诊断首行，详情已落盘 engine_fallback_log.txt
                        val engineWarn = if (eval.depth <= 0) {
                            " | 【引擎兜底】${StockfishBridge.lastDiagnosticInfo.lineSequence().firstOrNull() ?: "原因未知"}"
                        } else ""
                        
                        // 低置信格徽标 (bug_11 教训): 单格误分类嫌疑现场可见，全量详情在 cells_forensics.txt
                        val lowConfWarn = if (detailedResp.lowConfidenceCells.isNotEmpty()) {
                            " | ⚠疑格:${detailedResp.lowConfidenceCells.take(3).joinToString(",")}"
                        } else ""

                        transparentOverlay?.showSuggestion(
                            boardRect = res.boardRect,
                            moveInfo = eval,
                            isWhitePerspective = res.isWhitePerspective,
                            medianSim = detailedResp.medianSim,
                            occupiedCount = detailedResp.occupiedCount,
                            detectedPerspective = detailedResp.detectedPerspective
                        )

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@FloatingBubbleService,
                                "【检测成功】视角: $perspectiveName | Sim: ${String.format("%.3f", detailedResp.medianSim)} | 占位: ${detailedResp.occupiedCount}$engineWarn$lowConfWarn",
                                if (eval.depth <= 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    is UltraRobustClassifier.ClassificationResponse.Rejected -> {
                        transparentOverlay?.hide()
                        val rejectedForensics = buildString {
                            appendLine("LocateScore: ${String.format("%.1f", locateResult.score)}")
                            appendLine("LowConf: ${detailedResp.lowConfidenceCells.joinToString(" ")}")
                            appendLine("GateRejected: ${detailedResp.gateRejectedCells.joinToString(" ")}")
                        }
                        saveDebugArtifactsAsync(copyForDebug, boardRect, "REJECTED_${detailedResp.reason}", rejectedForensics)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@FloatingBubbleService,
                                "【门禁拦截】原因: ${detailedResp.reason} (Sim=${String.format("%.3f", detailedResp.medianSim)}, 占位=${detailedResp.occupiedCount}, 定位分=${String.format("%.0f", locateResult.score)})",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    null -> {
                        transparentOverlay?.hide()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FloatingBubbleService, "分类器未初始化", Toast.LENGTH_SHORT).show()
                        }
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
     * 优雅排空：保留当前可获取到的最后一帧有效 Image，若无则等待新帧到达，杜绝静态画面全排空导致的空指针回归
     */
    private suspend fun captureCurrentScreenBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        isCapturingFrame = true
        try {
            var latestImage: android.media.Image? = null
            while (true) {
                val next = reader.acquireLatestImage() ?: break
                latestImage?.close()
                latestImage = next
            }

            if (latestImage == null) {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 350L) {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        latestImage = image
                        break
                    }
                    delay(15)
                }
            }

            val image = latestImage ?: return@withContext null
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                return@withContext if (pixelStride == 4 && rowPadding == 0) {
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
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            isCapturingFrame = false
        }
    }

    private fun saveDebugArtifactsAsync(bitmap: Bitmap?, rect: android.graphics.Rect, fen: String, cellForensics: String = "") {
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
                txtFile.writeText("BoardRect: $rect\nFEN: $fen\n${cellForensics}Time: ${System.currentTimeMillis()}\n")
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
