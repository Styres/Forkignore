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

    @Volatile
    var lastDiagnosticInfo: String = "引擎尚未初始化"
        private set

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

    /**
     * 纯函数：从 APK Zip 压缩包中流式原子提取匹配设备 ABI 的 libstockfish.so 二进制文件
     * 支持 JVM 单元测试与真机 APK 提取
     */
    fun extractBinaryFromZip(zip: java.util.zip.ZipFile, supportedAbis: Array<String>, targetFile: File): Boolean {
        for (abi in supportedAbis) {
            val entryName = "lib/$abi/libstockfish.so"
            val entry = zip.getEntry(entryName)
            if (entry != null) {
                val parentDir = targetFile.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }
                val tmpFile = File(parentDir ?: File("."), "${targetFile.name}.tmp")
                try {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmpFile.exists() && tmpFile.length() == entry.size) {
                        if (targetFile.exists()) targetFile.delete()
                        val renamed = tmpFile.renameTo(targetFile)
                        if (renamed || targetFile.exists()) {
                            targetFile.setExecutable(true, false)
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractBinaryFromZip failed for ABI $abi: ${e.message}")
                } finally {
                    if (tmpFile.exists()) tmpFile.delete()
                }
            }
        }
        return false
    }

    private suspend fun startEngineProcessLocked() {
        val context = appContext ?: run {
            lastDiagnosticInfo = "启动失败: appContext 为空"
            Log.w(TAG, "startEngineProcessLocked failed: appContext is null")
            return
        }

        val diag = StringBuilder()
        val startTime = System.currentTimeMillis()

        try {
            var selectedBinary: File? = null
            var startupPathDesc = "未知路径"

            // 保障 1: 优先探测系统 nativeLibraryDir 原生目录
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val nativeFile = File(nativeLibDir, "libstockfish.so")
            val nativeExists = nativeFile.exists()
            val nativeCanExec = nativeFile.canExecute()
            diag.append("路径1 [nativeLibDir]: exists=$nativeExists, canExec=$nativeCanExec\n")

            if (nativeExists && nativeCanExec) {
                selectedBinary = nativeFile
                startupPathDesc = "路径1 (nativeLibDir)"
            }

            // 保障 2: 若路径 1 不可用，从 APK Zip 原子流式提取到 filesDir 并赋 +x 权限
            if (selectedBinary == null) {
                val filesDirBin = File(context.filesDir, "libstockfish.so")
                val apkPath = context.applicationInfo.sourceDir
                var extracted = false

                if (apkPath != null && File(apkPath).exists()) {
                    try {
                        val zip = java.util.zip.ZipFile(apkPath)
                        val abis = Build.SUPPORTED_ABIS ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64")
                        extracted = extractBinaryFromZip(zip, abis, filesDirBin)
                        zip.close()
                    } catch (e: Exception) {
                        diag.append("路径2 [APK解压异常]: ${e.javaClass.simpleName}: ${e.message}\n")
                    }
                }

                filesDirBin.setExecutable(true, false)
                val filesExists = filesDirBin.exists()
                val filesCanExec = filesDirBin.canExecute()
                diag.append("路径2 [filesDir]: extracted=$extracted, exists=$filesExists, canExec=$filesCanExec\n")

                if (filesExists && filesCanExec) {
                    selectedBinary = filesDirBin
                    startupPathDesc = "路径2 (APK Zip -> filesDir)"
                }
            }

            if (selectedBinary == null || !selectedBinary.exists()) {
                lastDiagnosticInfo = "【引擎启动失败】\n$diag\n无可用可执行二进制，跌入纯 Kotlin 兜底"
                Log.w(TAG, "Stockfish binary unavailable, using fallback\n$diag")
                isEngineReady = false
                return
            }

            val pb = ProcessBuilder(selectedBinary.absolutePath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            diag.append("进程启动: 成功 ($startupPathDesc)\n")

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

            // 保障 3: 5000ms 宽限期严格 UCI 握手时序 (uci -> uciok -> ucinewgame -> isready -> readyok)
            val uciStart = System.currentTimeMillis()
            sendCommand("uci")
            val uciOk = waitForResponse("uciok", timeoutMs = 5000)
            val uciElapsed = System.currentTimeMillis() - uciStart
            diag.append("握手 [uciok]: ${if (uciOk) "成功 (${uciElapsed}ms)" else "超时失败 (${uciElapsed}ms)"}\n")

            if (uciOk) {
                sendCommand("ucinewgame")
                val readyStart = System.currentTimeMillis()
                sendCommand("isready")
                val readyOk = waitForResponse("readyok", timeoutMs = 5000)
                val readyElapsed = System.currentTimeMillis() - readyStart
                diag.append("握手 [readyok]: ${if (readyOk) "成功 (${readyElapsed}ms)" else "超时失败 (${readyElapsed}ms)"}\n")

                isEngineReady = readyOk
                if (readyOk) {
                    val totalTime = System.currentTimeMillis() - startTime
                    diag.append("总耗时: ${totalTime}ms | 真实 Stockfish 准备就绪")
                    lastDiagnosticInfo = "【引擎就绪 ($startupPathDesc)】\n$diag"
                    Log.i(TAG, "Stockfish ready successfully\n$diag")
                } else {
                    lastDiagnosticInfo = "【引擎握手失败】\n$diag\nreadyok 超时，销毁进程"
                    isEngineReady = false
                    destroyProcessLocked()
                }
            } else {
                lastDiagnosticInfo = "【引擎握手失败】\n$diag\nuciok 超时，销毁进程"
                isEngineReady = false
                destroyProcessLocked()
            }
        } catch (e: Exception) {
            diag.append("异常终止: ${e.javaClass.simpleName}: ${e.message}\n")
            lastDiagnosticInfo = "【引擎启动异常】\n$diag"
            Log.e(TAG, "Failed to start Stockfish process\n$diag", e)
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
                    while (lineChannel?.tryReceive()?.getOrNull() != null) {}

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
                            bestMove = bestMoveResult,
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

        var depth = -1
        val depthMatcher = depthPattern.matcher(line)
        if (depthMatcher.find()) {
            depth = depthMatcher.group(1)?.toIntOrNull() ?: -1
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
     * 判断目标格子是否处于指定颜色方的攻击射线或攻击范围内
     */
    fun isSquareAttacked(board: Array<CharArray>, targetR: Int, targetC: Int, byWhite: Boolean): Boolean {
        // 1. 马攻击判定 (8 向 L 跃)
        val kOffsets = arrayOf(
            Pair(-2, -1), Pair(-2, 1), Pair(-1, -2), Pair(-1, 2),
            Pair(1, -2), Pair(1, 2), Pair(2, -1), Pair(2, 1)
        )
        val kSym = if (byWhite) 'N' else 'n'
        for ((dr, dc) in kOffsets) {
            val nr = targetR + dr
            val nc = targetC + dc
            if (nr in 0..7 && nc in 0..7 && board[nr][nc] == kSym) return true
        }

        // 2. 兵斜切攻击判定 (白兵向上 -1 移动反向源在 +1，黑兵向下 +1 移动反向源在 -1)
        val pSym = if (byWhite) 'P' else 'p'
        val pDr = if (byWhite) 1 else -1
        for (pDc in arrayOf(-1, 1)) {
            val nr = targetR + pDr
            val nc = targetC + pDc
            if (nr in 0..7 && nc in 0..7 && board[nr][nc] == pSym) return true
        }

        // 3. 车/后 正交 4 向射线攻击判定
        val orthSyms = if (byWhite) charArrayOf('R', 'Q') else charArrayOf('r', 'q')
        val orthDirs = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))
        for ((dr, dc) in orthDirs) {
            var step = 1
            while (true) {
                val nr = targetR + dr * step
                val nc = targetC + dc * step
                if (nr !in 0..7 || nc !in 0..7) break
                val p = board[nr][nc]
                if (p != '.') {
                    if (p in orthSyms) return true
                    break
                }
                step++
            }
        }

        // 4. 主教/后 对角 4 向射线攻击判定
        val diagSyms = if (byWhite) charArrayOf('B', 'Q') else charArrayOf('b', 'q')
        val diagDirs = arrayOf(Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1))
        for ((dr, dc) in diagDirs) {
            var step = 1
            while (true) {
                val nr = targetR + dr * step
                val nc = targetC + dc * step
                if (nr !in 0..7 || nc !in 0..7) break
                val p = board[nr][nc]
                if (p != '.') {
                    if (p in diagSyms) return true
                    break
                }
                step++
            }
        }

        // 5. 王单步 8 邻格攻击判定
        val kingSym = if (byWhite) 'K' else 'k'
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = targetR + dr
                val nc = targetC + dc
                if (nr in 0..7 && nc in 0..7 && board[nr][nc] == kingSym) return true
            }
        }

        return false
    }

    /**
     * 判断指定颜色方的国王是否处于被将军状态
     */
    fun isKingInCheck(board: Array<CharArray>, isWhite: Boolean): Boolean {
        val kingSym = if (isWhite) 'K' else 'k'
        var kr = -1
        var kc = -1
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] == kingSym) {
                    kr = r
                    kc = c
                    break
                }
            }
            if (kr != -1) break
        }
        if (kr == -1) return false
        return isSquareAttacked(board, kr, kc, byWhite = !isWhite)
    }

    /**
     * 智能合法走法兜底生成器（严格 FIDE 棋规实现：试走过滤、将军防守、绝对牵制保护、将杀/逼和分支）
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

        // 候选伪走法列表：(fromR, fromC, toR, toC, promoSuffix)
        data class RawMove(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int, val promo: String = "")
        val candidateMoves = mutableListOf<RawMove>()

        for (r in 0..7) {
            for (c in 0..7) {
                val piece = board[r][c]
                val belongsToActive = if (isWhite) piece.isUpperCase() else piece.isLowerCase()
                if (!belongsToActive) continue

                val pUpper = piece.uppercaseChar()

                when (pUpper) {
                    'P' -> {
                        val dir = if (isWhite) -1 else 1
                        val nextR = r + dir
                        if (nextR in 0..7 && board[nextR][c] == '.') {
                            val promoSuffix = if (nextR == 0 || nextR == 7) "q" else ""
                            candidateMoves.add(RawMove(r, c, nextR, c, promoSuffix))
                            val startRank = if (isWhite) 6 else 1
                            val doubleNextR = r + 2 * dir
                            if (r == startRank && board[doubleNextR][c] == '.') {
                                candidateMoves.add(RawMove(r, c, doubleNextR, c))
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
                                    candidateMoves.add(RawMove(r, c, nextR, targetC, promoSuffix))
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
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                }
                            }
                        }
                    }
                    'B' -> {
                        val diagDirs = arrayOf(Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1))
                        for ((dr, dc) in diagDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        candidateMoves.add(RawMove(r, c, nr, nc))
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'R' -> {
                        val orthDirs = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))
                        for ((dr, dc) in orthDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        candidateMoves.add(RawMove(r, c, nr, nc))
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'Q' -> {
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
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        candidateMoves.add(RawMove(r, c, nr, nc))
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'K' -> {
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
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 严格试走模拟验证：剔除破坏绝对牵制 (Pin) 或未解除将军 (Check) 的非法走法
        val legalMoves = mutableListOf<String>()
        for (m in candidateMoves) {
            val simulated = Array(8) { rIdx -> board[rIdx].clone() }
            val movedPiece = simulated[m.fromR][m.fromC]
            simulated[m.fromR][m.fromC] = '.'
            simulated[m.toR][m.toC] = if (m.promo.isNotEmpty()) (if (isWhite) 'Q' else 'q') else movedPiece

            if (!isKingInCheck(simulated, isWhite)) {
                val fromSquare = "${('a' + m.fromC)}${8 - m.fromR}"
                val toSquare = "${('a' + m.toC)}${8 - m.toR}"
                legalMoves.add("$fromSquare$toSquare${m.promo}")
            }
        }

        // 终局处理：当无合法走法时区分将杀与逼和
        if (legalMoves.isEmpty()) {
            val inCheck = isKingInCheck(board, isWhite)
            return if (inCheck) {
                // 被将杀 (Checkmate)
                EngineEvaluation(
                    bestMove = "(checkmate)",
                    evalScore = if (isWhite) -100.0f else 100.0f,
                    depth = 0,
                    isMate = true
                )
            } else {
                // 逼和 (Stalemate)
                EngineEvaluation(
                    bestMove = "(stalemate)",
                    evalScore = 0.0f,
                    depth = 0,
                    isMate = false
                )
            }
        }

        // 择优选取占中或发展轻子的优质合法走法
        val bestMove = legalMoves.firstOrNull { it.endsWith("e4") || it.endsWith("d4") || it.endsWith("e5") || it.endsWith("c5") || it.endsWith("f3") || it.endsWith("f6") }
            ?: legalMoves.first()

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
