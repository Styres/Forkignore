package com.chess.copilot.engine

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.regex.Pattern

/**
 * 响应式 Stockfish UCI 引擎管道桥接（V7 双保险执行与严格棋规版）
 * 核心机制：
 * 1. nativeLibraryDir 优先 + filesDir 自动复制与 setExecutable(true) 兜底双保险
 * 2. 专职后台 I/O 读协程持续阻塞读取管道，通过 Channel<String> 异步推流
 * 3. 严格 UCI 握手与全链路诊断日志
 * 4. 彻底重构 evaluateFallback 走法规则（主教斜走、车直走、后八向），标明 depth=0 兜底
 * 5. 严格遵守"fallback 结果绝对不写入 evalCache"规则
 */
object StockfishBridge {

    private const val TAG = "StockfishBridge"

    data class EngineEvaluation(
        val bestMove: String,     // UCI 走法，如 "e2e4", "e7e8q"
        val evalScore: Float,     // 局面评分，如 +0.58, -1.20
        val depth: Int,           // 搜索深度 (真实引擎 >= 10, 兜底为 0)
        val isMate: Boolean = false
    )

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var lineChannel: Channel<String>? = null
    private var readerJob: Job? = null

    @Volatile
    private var isEngineReady: Boolean = false
    private var appContext: Context? = null

    private val engineMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    // LRU 缓存：缓存最近 32 个局面的真实分析结果
    private val evalCache = LruCache<String, EngineEvaluation>(32)

    private val bestMovePattern = Pattern.compile("^bestmove\\s+([a-h][1-8][a-h][1-8][qrbnQRBN]?|\\(none\\))")
    private val scoreCpPattern = Pattern.compile("score\\s+cp\\s+(-?\\d+)")
    private val scoreMatePattern = Pattern.compile("score\\s+mate\\s+(-?\\d+)")
    private val depthPattern = Pattern.compile("depth\\s+(\\d+)")

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isEngineReady && process != null) return

        scope.launch {
            engineMutex.withLock {
                if (isEngineReady && process != null) return@withLock
                startEngineProcessLocked()
            }
        }
    }

    private suspend fun startEngineProcessLocked() {
        val context = appContext ?: run {
            Log.w(TAG, "startEngineProcessLocked failed: appContext is null")
            return
        }

        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            var binaryFile = File(nativeLibDir, "libstockfish.so")
            Log.i(TAG, "Locating Stockfish binary at nativeLibraryDir: ${binaryFile.absolutePath}, exists=${binaryFile.exists()}, canExec=${binaryFile.canExecute()}")

            // 双保险方案：若 nativeLibraryDir 无法执行，复制到 filesDir 赋予 +x 权限
            if (!binaryFile.exists() || !binaryFile.canExecute()) {
                val fallbackBin = File(context.filesDir, "libstockfish.so")
                if (binaryFile.exists()) {
                    try {
                        if (!fallbackBin.exists() || fallbackBin.length() != binaryFile.length()) {
                            Log.i(TAG, "Copying libstockfish.so to filesDir: ${fallbackBin.absolutePath}")
                            FileInputStream(binaryFile).use { input ->
                                FileOutputStream(fallbackBin).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        fallbackBin.setExecutable(true, false)
                        Log.i(TAG, "filesDir binary prepared: exists=${fallbackBin.exists()}, canExec=${fallbackBin.canExecute()}")
                        binaryFile = fallbackBin
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to copy libstockfish.so to filesDir: ${e.message}")
                    }
                }
            }

            if (!binaryFile.exists()) {
                Log.w(TAG, "libstockfish.so binary not found, fallback will be used")
                isEngineReady = false
                return
            }

            val pb = ProcessBuilder(binaryFile.absolutePath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            Log.i(TAG, "Stockfish process started successfully: $p")

            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            val br = BufferedReader(InputStreamReader(p.inputStream))
            reader = br

            // 创建专属非阻塞事件流通道
            val channel = Channel<String>(Channel.UNLIMITED)
            lineChannel = channel

            // 启动后台阻塞读取协程
            readerJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val line = br.readLine() ?: break
                        channel.send(line)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Reader loop exited: ${e.message}")
                } finally {
                    channel.close()
                }
            }

            // 严格 UCI 握手时序 (uci -> uciok -> ucinewgame -> isready -> readyok)
            sendCommand("uci")
            val uciOk = waitForResponse("uciok", timeoutMs = 2000)
            Log.i(TAG, "UCI handshake response 'uciok': $uciOk")

            if (uciOk) {
                sendCommand("ucinewgame")
                sendCommand("isready")
                val readyOk = waitForResponse("readyok", timeoutMs = 2000)
                Log.i(TAG, "UCI handshake response 'readyok': $readyOk")
                isEngineReady = readyOk
            } else {
                Log.w(TAG, "UCI handshake timed out without uciok, destroying process")
                isEngineReady = false
                destroyProcessLocked()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Stockfish process: ${e.message}", e)
            destroyProcessLocked()
        }
    }

    private fun sendCommand(cmd: String) {
        try {
            writer?.let {
                it.write(cmd)
                it.newLine()
                it.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendCommand '$cmd' failed: ${e.message}")
        }
    }

    private suspend fun waitForResponse(expected: String, timeoutMs: Long): Boolean {
        val channel = lineChannel ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break

            val line = withTimeoutOrNull(remaining) {
                try {
                    channel.receive()
                } catch (_: Exception) {
                    null
                }
            } ?: break

            if (line.contains(expected)) {
                return true
            }
        }
        return false
    }

    /**
     * 对 FEN 执行深度分析并返回推荐走法与评估分（带结果缓存与自动重启自愈）
     */
    suspend fun evaluateFen(fen: String, moveTimeMs: Long = 120): EngineEvaluation = withContext(Dispatchers.IO) {
        // 1. 优先命中 LRU 缓存，保证静止盘面建议确定无跳变
        val cached = evalCache.get(fen)
        if (cached != null) {
            return@withContext cached
        }

        engineMutex.withLock {
            // 双重校验缓存
            val reCheck = evalCache.get(fen)
            if (reCheck != null) {
                return@withContext reCheck
            }

            // 2. 引擎自愈机制 (Auto-Respawn)
            if (!isEngineReady || process == null) {
                startEngineProcessLocked()
            }

            if (isEngineReady && process != null && lineChannel != null) {
                try {
                    // 排空历史残余文本行
                    while (true) {
                        val poll = lineChannel?.tryReceive()?.getOrNull() ?: break
                    }

                    sendCommand("isready")
                    val readyOk = waitForResponse("readyok", timeoutMs = 800)
                    if (!readyOk) {
                        Log.w(TAG, "Stockfish readyok timeout before evaluate, restarting process")
                        destroyProcessLocked()
                        startEngineProcessLocked()
                    }

                    val currentChannel = lineChannel
                    if (currentChannel == null || !isEngineReady || process == null) {
                        Log.w(TAG, "Stockfish unavailable after restart, fallback will be used")
                        destroyProcessLocked()
                        val fallback = evaluateFallback(fen)
                        return@withContext fallback
                    }

                    sendCommand("position fen $fen")
                    sendCommand("go movetime $moveTimeMs")

                    var lastEval: EngineEvaluation? = null
                    var bestMoveResult: String? = null
                    val deadline = System.currentTimeMillis() + moveTimeMs + 1000L

                    // 响应式挂起读取输出
                    while (System.currentTimeMillis() < deadline) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break

                        val line = withTimeoutOrNull(remaining) {
                            try {
                                currentChannel.receive()
                            } catch (_: Exception) {
                                null
                            }
                        } ?: break

                        val parsedInfo = parseInfoLine(line)
                        if (parsedInfo != null) {
                            lastEval = parsedInfo
                        }

                        val bm = parseBestMoveLine(line)
                        if (bm != null) {
                            bestMoveResult = bm
                            break
                        }
                    }

                    if (bestMoveResult != null && bestMoveResult != "(none)") {
                        val result = EngineEvaluation(
                            bestMove = bestMoveResult!!,
                            evalScore = lastEval?.evalScore ?: 0.0f,
                            depth = lastEval?.depth ?: 12,
                            isMate = lastEval?.isMate ?: false
                        )
                        Log.i(TAG, "Stockfish evaluate success: bestMove=${result.bestMove}, depth=${result.depth}, score=${result.evalScore}")
                        evalCache.put(fen, result)
                        return@withContext result
                    }

                    Log.w(TAG, "Stockfish evaluateFen timed out without bestmove, resetting process")
                    destroyProcessLocked()
                } catch (e: Exception) {
                    Log.w(TAG, "Stockfish evaluateFen exception: ${e.message}", e)
                    destroyProcessLocked()
                }
            }

            // 3. 双重兜底：纯 Kotlin 合法走法评估（安全兜底，且 fallback 结果绝对严禁写入 evalCache）
            Log.w(TAG, "Using fallback heuristic evaluator for FEN: $fen")
            val fallback = evaluateFallback(fen)
            return@withContext fallback
        }
    }

    private fun destroyProcessLocked() {
        try {
            process?.destroy()
        } catch (_: Exception) {}
        try {
            readerJob?.cancel()
            lineChannel?.close()
            writer?.close()
            reader?.close()
        } catch (_: Exception) {}

        process = null
        writer = null
        reader = null
        lineChannel = null
        readerJob = null
        isEngineReady = false
    }

    fun parseBestMoveLine(line: String): String? {
        val matcher = bestMovePattern.matcher(line.trim())
        return if (matcher.find()) matcher.group(1) else null
    }

    fun parseInfoLine(line: String): EngineEvaluation? {
        if (!line.startsWith("info ")) return null

        var depth = 10
        val depthMatcher = depthPattern.matcher(line)
        if (depthMatcher.find()) {
            depth = depthMatcher.group(1)?.toIntOrNull() ?: 10
        }

        val mateMatcher = scoreMatePattern.matcher(line)
        if (mateMatcher.find()) {
            val mateSteps = mateMatcher.group(1)?.toIntOrNull() ?: 0
            val score = if (mateSteps > 0) 100.0f else -100.0f
            return EngineEvaluation(bestMove = "", evalScore = score, depth = depth, isMate = true)
        }

        val cpMatcher = scoreCpPattern.matcher(line)
        if (cpMatcher.find()) {
            val cp = cpMatcher.group(1)?.toIntOrNull() ?: 0
            val score = cp / 100.0f
            return EngineEvaluation(bestMove = "", evalScore = score, depth = depth, isMate = false)
        }

        return null
    }

    /**
     * 智能合法走法兜底生成器（严格棋规实现：主教斜走、车直走、后八向、马日字、兵直行斜吃）
     */
    fun evaluateFallback(fen: String): EngineEvaluation {
        val parts = fen.split(" ")
        val isWhite = if (parts.size > 1) parts[1] == "w" else true
        val rows = parts[0].split("/")

        val board = Array(8) { r ->
            val rowStr = if (r < rows.size) rows[r] else "8"
            val expanded = StringBuilder()
            for (ch in rowStr) {
                if (ch.isDigit()) {
                    repeat(ch - '0') { expanded.append('.') }
                } else {
                    expanded.append(ch)
                }
            }
            while (expanded.length < 8) expanded.append('.')
            expanded.toString().toCharArray()
        }

        val legalMoves = mutableListOf<String>()
        for (r in 0..7) {
            for (c in 0..7) {
                val piece = board[r][c]
                val belongsToActive = if (isWhite) piece.isUpperCase() else piece.isLowerCase()
                if (!belongsToActive) continue

                val fromSquare = "${('a' + c)}${8 - r}"
                val pUpper = piece.uppercaseChar()

                when (pUpper) {
                    'P' -> {
                        val dir = if (isWhite) -1 else 1
                        val nextR = r + dir
                        if (nextR in 0..7 && board[nextR][c] == '.') {
                            val promoSuffix = if (nextR == 0 || nextR == 7) "q" else ""
                            legalMoves.add("$fromSquare${('a' + c)}${8 - nextR}$promoSuffix")
                            val startRank = if (isWhite) 6 else 1
                            val doubleNextR = r + 2 * dir
                            if (r == startRank && board[doubleNextR][c] == '.') {
                                legalMoves.add("$fromSquare${('a' + c)}${8 - doubleNextR}")
                            }
                        }
                        // 斜向吃子
                        for (dc in arrayOf(-1, 1)) {
                            val targetC = c + dc
                            if (nextR in 0..7 && targetC in 0..7) {
                                val target = board[nextR][targetC]
                                val isEnemy = if (isWhite) (target != '.' && target.isLowerCase())
                                else (target != '.' && target.isUpperCase())
                                if (isEnemy) {
                                    val promoSuffix = if (nextR == 0 || nextR == 7) "q" else ""
                                    legalMoves.add("$fromSquare${('a' + targetC)}${8 - nextR}$promoSuffix")
                                }
                            }
                        }
                    }
                    'N' -> {
                        val offsets = arrayOf(
                            Pair(-2, -1), Pair(-2, 1), Pair(-1, -2), Pair(-1, 2),
                            Pair(1, -2), Pair(1, 2), Pair(2, -1), Pair(2, 1)
                        )
                        for ((dr, dc) in offsets) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val target = board[nr][nc]
                                val isEnemyOrEmpty = if (isWhite) (target == '.' || target.isLowerCase())
                                else (target == '.' || target.isUpperCase())
                                if (isEnemyOrEmpty) {
                                    legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                }
                            }
                        }
                    }
                    'B' -> {
                        // 主教：仅沿 4 个斜对角线移动
                        val diagDirs = arrayOf(Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1))
                        for ((dr, dc) in diagDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'R' -> {
                        // 车：仅沿 4 个正交方向移动
                        val orthDirs = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))
                        for ((dr, dc) in orthDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'Q' -> {
                        // 后：8 个方向射线移动
                        val allDirs = arrayOf(
                            Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1),
                            Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1)
                        )
                        for ((dr, dc) in allDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'K' -> {
                        // 王：8 个方向单步移动
                        val kingDirs = arrayOf(
                            Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1),
                            Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1)
                        )
                        for ((dr, dc) in kingDirs) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val target = board[nr][nc]
                                val isEnemyOrEmpty = if (isWhite) (target == '.' || target.isLowerCase())
                                else (target == '.' || target.isUpperCase())
                                if (isEnemyOrEmpty) {
                                    legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
                                }
                            }
                        }
                    }
                }
            }
        }

        val bestMove = legalMoves.firstOrNull { it.endsWith("e4") || it.endsWith("d4") || it.endsWith("e5") || it.endsWith("c5") || it.endsWith("f3") || it.endsWith("f6") }
            ?: legalMoves.firstOrNull()
            ?: (if (isWhite) "e2e4" else "e7e5")

        return EngineEvaluation(
            bestMove = bestMove,
            evalScore = 0.0f,
            depth = 0,
            isMate = false
        )
    }

    fun release() {
        scope.launch {
            engineMutex.withLock {
                try {
                    if (isEngineReady) {
                        sendCommand("quit")
                    }
                } catch (_: Exception) {}
                destroyProcessLocked()
                evalCache.evictAll()
            }
        }
    }
}
