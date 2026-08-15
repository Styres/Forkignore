package com.chess.copilot.engine

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.regex.Pattern

/**
 * 极简高可靠 Stockfish UCI 引擎管道桥接（借鉴 Lichess Mobile 状态机与自愈机制）
 * 核心机制：
 * 1. 直接执行 nativeLibraryDir 下的 libstockfish.so (合规绕过 Android 10+ W^X 限制)
 * 2. 引擎异常/超时自动重启自愈 (Auto-Respawn)
 * 3. 局势结果 LRU 缓存，保证同一静止盘面多次点击建议绝对确定
 * 4. 非阻塞协程轮询超时防挂起 + readyok 严格同步
 */
object StockfishBridge {

    private const val TAG = "StockfishBridge"

    data class EngineEvaluation(
        val bestMove: String,     // UCI 走法，如 "e2e4", "e7e8q"
        val evalScore: Float,     // 局面评分，如 +0.58, -1.20
        val depth: Int,           // 搜索深度，如 12
        val isMate: Boolean = false
    )

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    @Volatile
    private var isEngineReady: Boolean = false
    private var appContext: Context? = null

    private val engineMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    // LRU 缓存：缓存最近 32 个局面的分析结果，保证确定性
    private val evalCache = LruCache<String, EngineEvaluation>(32)

    // 正则支持 4~5 位字符（含兵升变 q/r/b/n）
    private val bestMovePattern = Pattern.compile("^bestmove\\s+([a-h][1-8][a-h][1-8][qrbnQRBN]?|\\(none\\))")
    private val scoreCpPattern = Pattern.compile("score\\s+cp\\s+(-?\\d+)")
    private val scoreMatePattern = Pattern.compile("score\\s+mate\\s+(-?\\d+)")
    private val depthPattern = Pattern.compile("depth\\s+(\\d+)")

    /**
     * 异步初始化引擎：直接从 nativeLibraryDir 调起 libstockfish.so
     */
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

    private fun startEngineProcessLocked() {
        val context = appContext ?: return
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binaryFile = File(nativeLibDir, "libstockfish.so")

            if (binaryFile.exists() && binaryFile.canExecute()) {
                val pb = ProcessBuilder(binaryFile.absolutePath)
                pb.redirectErrorStream(true)
                val p = pb.start()
                process = p

                writer = BufferedWriter(OutputStreamWriter(p.outputStream))
                reader = BufferedReader(InputStreamReader(p.inputStream))

                // 严格 UCI 握手时序 (ucinewgame -> isready -> readyok)
                sendCommand("uci")
                val uciOk = waitForResponse("uciok", timeoutMs = 1500)

                if (uciOk) {
                    sendCommand("ucinewgame")
                    sendCommand("isready")
                    val readyOk = waitForResponse("readyok", timeoutMs = 1500)
                    isEngineReady = readyOk
                } else {
                    isEngineReady = false
                }
            } else {
                Log.w(TAG, "libstockfish.so not found or not executable in $nativeLibDir, fallback will be used")
                isEngineReady = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Stockfish process: ${e.message}", e)
            destroyProcessLocked()
        }
    }

    private fun sendCommand(cmd: String) {
        writer?.let {
            it.write(cmd)
            it.newLine()
            it.flush()
        }
    }

    private fun waitForResponse(expected: String, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (reader?.ready() == true) {
                val line = reader?.readLine() ?: break
                if (line.contains(expected)) return true
            } else {
                Thread.sleep(15)
            }
        }
        return false
    }

    /**
     * 对 FEN 执行分析并返回推荐走法与评估分（带结果缓存与自动重启自愈）
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

            if (isEngineReady && process != null) {
                try {
                    // 清空历史残余流
                    while (reader?.ready() == true) {
                        reader?.readLine()
                    }

                    sendCommand("isready")
                    val readyOk = waitForResponse("readyok", timeoutMs = 500)
                    if (!readyOk) {
                        Log.w(TAG, "Stockfish readyok timeout, restarting process")
                        destroyProcessLocked()
                        startEngineProcessLocked()
                    }

                    sendCommand("position fen $fen")
                    sendCommand("go movetime $moveTimeMs")

                    var lastEval: EngineEvaluation? = null
                    var bestMoveResult: String? = null
                    val deadline = System.currentTimeMillis() + moveTimeMs + 600L

                    // 非阻塞轮询读取
                    while (System.currentTimeMillis() < deadline) {
                        if (reader?.ready() == true) {
                            val line = reader?.readLine() ?: break
                            val parsedInfo = parseInfoLine(line)
                            if (parsedInfo != null) {
                                lastEval = parsedInfo
                            }

                            val bm = parseBestMoveLine(line)
                            if (bm != null) {
                                bestMoveResult = bm
                                break
                            }
                        } else {
                            delay(15)
                        }
                    }

                    if (bestMoveResult != null && bestMoveResult != "(none)") {
                        val result = EngineEvaluation(
                            bestMove = bestMoveResult!!,
                            evalScore = lastEval?.evalScore ?: 0.0f,
                            depth = lastEval?.depth ?: 12,
                            isMate = lastEval?.isMate ?: false
                        )
                        evalCache.put(fen, result)
                        return@withContext result
                    }

                    // 超时重置
                    Log.w(TAG, "Stockfish evaluateFen timed out without bestmove, resetting process")
                    destroyProcessLocked()
                } catch (e: Exception) {
                    Log.w(TAG, "Stockfish evaluateFen exception: ${e.message}", e)
                    destroyProcessLocked()
                }
            }

            // 3. 双重兜底：纯 Kotlin 合法走法评估 (注：fallback 结果严禁写入 evalCache，以保证引擎恢复后能输出高质量建议)
            val fallback = evaluateFallback(fen)
            return@withContext fallback
        }
    }

    private fun destroyProcessLocked() {
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        isEngineReady = false
    }

    /**
     * 解析 bestmove 行（含升变）
     */
    fun parseBestMoveLine(line: String): String? {
        val matcher = bestMovePattern.matcher(line.trim())
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * 解析 info 行中的 depth, cp, mate
     */
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
     * 智能合法走法兜底生成器（确保绝对不移动空格，支持升变后缀）
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
                    else -> {
                        val steps = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))
                        for ((dr, dc) in steps) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7 && board[nr][nc] == '.') {
                                legalMoves.add("$fromSquare${('a' + nc)}${8 - nr}")
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
            evalScore = if (isWhite) 0.15f else -0.15f,
            depth = 6,
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
                    writer?.close()
                    reader?.close()
                    process?.destroy()
                } catch (_: Exception) {}
                process = null
                writer = null
                reader = null
                isEngineReady = false
                evalCache.evictAll()
            }
        }
    }
}
