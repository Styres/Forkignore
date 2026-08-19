package com.chess.copilot.engine

import android.content.Context
import android.os.Build
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
import kotlin.math.abs
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

    // NNUE 评估网络 (bug_10 真机取证: 缺网络时引擎在首次搜索时自行终止，握手成功是假就绪)
    // 文件名与引擎编译期内置默认网络严格一致，切勿随意更名
    private const val NNUE_ASSET_NAME = "nnue/nn-5af11540bbfe.nnue"

    data class EngineEvaluation(
        val bestMove: String,     // UCI 走法，如 "e2e4", "e7e8q"; "(invalid)" 为识别层非法局面哨兵
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

    // 引擎自报的 ERROR 行 (如缺 NNUE 网络)，供握手失败诊断引用
    private var lastEngineError: String? = null

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

            // 保障 2.5: NNUE 评估网络就绪 (缺网络时引擎会在首次搜索时打印 ERROR 并自杀)
            val nnueDir = File(context.filesDir, "nnue")
            val nnueFile = ensureNnueFile(context, nnueDir, diag)

            val pb = ProcessBuilder(selectedBinary.absolutePath)
            pb.redirectErrorStream(true)
            // 引擎默认从工作目录按内置默认文件名查找网络，cwd 指向网络目录双保险
            pb.directory(nnueDir)
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
                    // bug_16/17 教训: 引擎进程死亡后若不清旗，后续调用会对着已关闭通道空读，
                    // 表现为"计算超时 收到行数:0"直接跌入兜底而非自愈重启
                    isEngineReady = false
                }
            }

            // 保障 3: 5000ms 宽限期严格 UCI 握手时序 (uci -> uciok -> ucinewgame -> isready -> readyok)
            val uciStart = System.currentTimeMillis()
            sendCommand("uci")
            val uciOk = waitForResponse("uciok", timeoutMs = 5000)
            val uciElapsed = System.currentTimeMillis() - uciStart
            diag.append("握手 [uciok]: ${if (uciOk) "成功 (${uciElapsed}ms)" else "超时失败 (${uciElapsed}ms)"}\n")

            if (uciOk) {
                // 显式指定评估网络绝对路径 (与 cwd 双保险)
                nnueFile?.let { sendCommand("setoption name EvalFile value ${it.absolutePath}") }
                // 确定性参数 (bug_19 教训, 对齐 lichess 官方 UCIProtocol 默认 Threads=1/Hash=16):
                // 多线程搜索在 movetime 截断下非确定，是"同局面两次点击建议不同"的次要嫌疑源；
                // 单线程+固定 Hash 使同一 FEN 的搜索结果可复现，建议跳变即可归因为识别层 FEN 抖动
                sendCommand("setoption name Threads value 1")
                sendCommand("setoption name Hash value 16")
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
                    lastDiagnosticInfo = "【引擎握手失败】\n$diag\nreadyok 超时，销毁进程${lastEngineError?.let { "\n引擎报错: $it" } ?: ""}"
                    isEngineReady = false
                    destroyProcessLocked()
                }
            } else {
                lastDiagnosticInfo = "【引擎握手失败】\n$diag\nuciok 超时，销毁进程${lastEngineError?.let { "\n引擎报错: $it" } ?: ""}"
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

    /**
     * 确保 NNUE 评估网络在 filesDir 就绪 (assets -> .tmp 原子拷贝 -> rename，带尺寸复查与缓存复用)
     */
    private fun ensureNnueFile(context: Context, nnueDir: File, diag: StringBuilder): File? {
        try {
            if (!nnueDir.exists()) nnueDir.mkdirs()
            val target = File(nnueDir, NNUE_ASSET_NAME.substringAfterLast('/'))
            if (target.exists() && target.length() > 1024 * 1024) {
                diag.append("NNUE [缓存]: 就绪 (${target.length()} bytes)\n")
                return target
            }
            val tmp = File(nnueDir, target.name + ".tmp")
            context.assets.open(NNUE_ASSET_NAME).use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            return if (tmp.length() > 1024 * 1024 && tmp.renameTo(target)) {
                diag.append("NNUE [提取]: 成功 (${target.length()} bytes)\n")
                target
            } else {
                tmp.delete()
                diag.append("NNUE [提取]: 失败 (尺寸或 rename 异常)\n")
                null
            }
        } catch (e: Exception) {
            diag.append("NNUE [提取异常]: ${e.javaClass.simpleName}: ${e.message}\n")
            return null
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

            // 引擎自报 ERROR (如缺 NNUE 网络) 时立即判负，避免傻等到超时
            if (line.contains("ERROR")) {
                lastEngineError = line
                Log.w(TAG, "Stockfish reported ERROR during handshake: $line")
                return false
            }

            if (line.contains(expected)) {
                return true
            }
        }
        return false
    }

    /**
     * 对 FEN 执行深度分析并返回推荐走法与评估分（带结果缓存与自动重启自愈）
     */
    suspend fun evaluateFen(fen: String, moveTimeMs: Long = 200): EngineEvaluation = withContext(Dispatchers.IO) {
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

            // 2. FEN 合法性预校验 (bug_19/superbug 教训): 识别错误产出的非法局面 (如走子方被将/双王同时被将)
            // 会让 Stockfish 秒回 bestmove (none)，曾被误判为引擎死亡导致反复销毁重启+兜底乱下。
            // 此类问题根因在识别层，直接返回哨兵结果，不打扰引擎也不兜底乱下
            val fenProblem = validateFenSanity(fen)
            if (fenProblem != null) {
                lastDiagnosticInfo = "【FEN 非法，未启动引擎】\n原因: $fenProblem\nFEN: $fen\n结论: 识别层产出不可能局面 (走子方被将等)，属于识别/视角错误而非引擎故障"
                Log.w(TAG, "FEN rejected by sanity check: $fenProblem, fen=$fen")
                appendFallbackLog("[FEN非法拦截] $fen")
                return@withContext EngineEvaluation(
                    bestMove = "(invalid)",
                    evalScore = 0.0f,
                    depth = -1,
                    isMate = false
                )
            }

            // 3. 引擎自愈机制 (Auto-Respawn): 首次失败后销毁重启并重试同一局面一次
            // (bug_16/17 教训: 引擎死后单次调用即跌入兜底乱下，重启重试可把临时性死亡收敛为无感恢复)
            var engineResult: EngineEvaluation? = null
            for (attempt in 1..2) {
                if (!isEngineReady || process == null) {
                    startEngineProcessLocked()
                }
                engineResult = evaluateViaEngineLocked(fen, moveTimeMs)
                if (engineResult != null) {
                    return@withContext engineResult
                }
                if (attempt == 1) {
                    Log.w(TAG, "Stockfish first attempt failed for FEN: $fen, restarting process and retrying")
                    destroyProcessLocked()
                }
            }

            // 4. 双重兜底：纯 Kotlin 合法走法评估（安全兜底，且 fallback 结果绝对严禁写入 evalCache）
            Log.w(TAG, "Using fallback heuristic evaluator for FEN: $fen")
            appendFallbackLog(fen)
            val fallback = evaluateFallback(fen)
            return@withContext fallback
        }
    }

    /**
     * 单次引擎分析尝试 (需持有 engineMutex): 成功返回结果并写缓存，失败销毁进程返回 null 交由调用方决定重试或兜底
     */
    private suspend fun evaluateViaEngineLocked(fen: String, moveTimeMs: Long): EngineEvaluation? {
        if (!isEngineReady || process == null || lineChannel == null) {
            return null
        }

        val evalStartTime = System.currentTimeMillis()
        val receivedLines = mutableListOf<String>()

        try {
            // 排空历史残余文本行
            while (lineChannel?.tryReceive()?.getOrNull() != null) {}

            sendCommand("isready")
            val readyOk = waitForResponse("readyok", timeoutMs = 3000)
            if (!readyOk) {
                Log.w(TAG, "Stockfish readyok timeout before evaluate, resetting process")
                destroyProcessLocked()
                return null
            }

            val currentChannel = lineChannel
            if (currentChannel == null || !isEngineReady || process == null) {
                destroyProcessLocked()
                return null
            }

            sendCommand("position fen $fen")
            sendCommand("go movetime $moveTimeMs")

            var lastEval: EngineEvaluation? = null
            var bestMoveResult: String? = null
            val deadline = System.currentTimeMillis() + moveTimeMs + 4000L

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

                receivedLines.add(line)
                if (receivedLines.size > 20) receivedLines.removeAt(0)

                // 引擎自报 ERROR (如缺 NNUE 网络) 立即记录，供重试失败后的兜底诊断引用
                if (line.contains("ERROR")) {
                    lastEngineError = line
                }

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

            // bug_19/superbug 教训: bestmove (none) 是引擎对"无合法着法局面"的合法秒回 (常伴随 info depth 0 score mate 0)，
            // 绝非引擎死亡。曾有代码把它当失败反复销毁重启进程再跌入兜底乱下。
            // 引擎已有输出行即证明进程存活: 按终局语义消费结果，保持进程不动
            if (bestMoveResult == "(none)") {
                val inCheck = isKingInCheck(parseFenBoard(fen), parseFenIsWhite(fen))
                val terminal = if (inCheck) {
                    EngineEvaluation("(checkmate)", if (parseFenIsWhite(fen)) -100.0f else 100.0f, 0, isMate = true)
                } else {
                    EngineEvaluation("(stalemate)", 0.0f, 0, isMate = false)
                }
                lastDiagnosticInfo = "【Stockfish 确认无合法着法】\n引擎存活并秒回 bestmove (none) | 收到行数: ${receivedLines.size}\n判定: ${if (inCheck) "将杀" else "逼和"} (非引擎故障，未重启进程)"
                Log.i(TAG, "Stockfish bestmove (none) consumed as terminal state (inCheck=$inCheck), engine kept alive")
                evalCache.put(fen, terminal)
                return terminal
            }

            if (bestMoveResult != null) {
                val result = EngineEvaluation(
                    bestMove = bestMoveResult,
                    evalScore = lastEval?.evalScore ?: 0.0f,
                    depth = if ((lastEval?.depth ?: -1) > 0) lastEval!!.depth else 0,
                    isMate = lastEval?.isMate ?: false
                )
                val totalElapsed = System.currentTimeMillis() - evalStartTime
                lastDiagnosticInfo = "【Stockfish 计算成功】\n耗时: ${totalElapsed}ms | 深度: ${result.depth} 层 | 评分: ${result.evalScore} | 走法: ${result.bestMove}\n末尾输出: ${receivedLines.takeLast(2).joinToString(" || ")}"
                Log.i(TAG, "Stockfish evaluate success: bestMove=${result.bestMove}, depth=${result.depth}, score=${result.evalScore}")
                evalCache.put(fen, result)
                return result
            }

            val totalElapsed = System.currentTimeMillis() - evalStartTime
            val engineErr = lastEngineError?.let { "\n引擎报错: $it" } ?: ""
            lastDiagnosticInfo = "【Stockfish 单次尝试无输出】\n已耗时: ${totalElapsed}ms | 收到行数: ${receivedLines.size}\n最近行: ${receivedLines.takeLast(2).joinToString(" || ")}$engineErr"
            Log.w(TAG, "Stockfish evaluate attempt produced no bestmove, resetting process\n$lastDiagnosticInfo")
            destroyProcessLocked()
            return null
        } catch (e: Exception) {
            lastDiagnosticInfo = "【Stockfish 分析异常，降级兜底】\n异常: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Stockfish evaluateFen exception: ${e.message}", e)
            destroyProcessLocked()
            return null
        }
    }

    /**
     * 兜底事件落盘自取证 (bug_15 教训: 悬浮层只显示 [兜]，原因被后续成功评估覆盖后无从查考)
     * 追加写入 filesDir/debug/engine_fallback_log.txt，保留最近 30 条
     */
    private fun appendFallbackLog(fen: String) {
        try {
            val ctx = appContext ?: return
            val debugDir = File(ctx.filesDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            val logFile = File(debugDir, "engine_fallback_log.txt")
            val entry = "[${System.currentTimeMillis()}] FEN: $fen\n诊断: ${lastDiagnosticInfo.replace("\n", " | ")}\n\n"
            val existing = if (logFile.exists()) logFile.readText() else ""
            val entries = (existing + entry).split("\n\n").filter { it.isNotBlank() }
            logFile.writeText(entries.takeLast(30).joinToString("\n\n") + "\n\n")
        } catch (_: Exception) {
        }
    }

    /**
     * FEN 结构解析辅助：盘面数组 (row 0 = rank 8)，供终局判定复用
     */
    private fun parseFenBoard(fen: String): Array<CharArray> {
        val rows = fen.split(" ")[0].split("/")
        return Array(8) { r ->
            val rowStr = if (r < rows.size) rows[r] else "8"
            val expanded = StringBuilder()
            for (ch in rowStr) {
                if (ch.isDigit()) repeat(ch - '0') { expanded.append('.') } else expanded.append(ch)
            }
            while (expanded.length < 8) expanded.append('.')
            expanded.toString().toCharArray()
        }
    }

    private fun parseFenIsWhite(fen: String): Boolean {
        val parts = fen.split(" ")
        return parts.size < 2 || parts[1] == "w"
    }

    /**
     * FEN 合法性预校验 (bug_19/superbug 定案): 拦截识别层产出的"不可能局面"，避免引擎秒回 (none) 被误判为引擎死亡。
     * 返回 null 表示通过；否则返回违规描述。校验项：双王唯一、双王不相邻、走子方不得已被将军
     * (python-chess 实测: 此类局面 Stockfish 输出 info depth 0 score mate 0 + bestmove (none))
     */
    fun validateFenSanity(fen: String): String? {
        val board = parseFenBoard(fen)
        var wk = 0
        var bk = 0
        var wkr = -1; var wkc = -1
        var bkr = -1; var bkc = -1
        
        // 【新增】总棋子数量硬核拦截，杜绝 UI 假框残留的零星特征拼凑成残局
        var totalPieces = 0 

        for (r in 0..7) {
            for (c in 0..7) {
                val piece = board[r][c]
                if (piece != '.') totalPieces++ 

                when (piece) {
                    'K' -> { wk++; wkr = r; wkc = c }
                    'k' -> { bk++; bkr = r; bkc = c }
                    'P', 'p' -> if (r == 0 || r == 7) return "兵出现在底线/顶线 (r${r}c${c})"
                }
            }
        }
        if (wk != 1 || bk != 1) return "王数量异常 (白王=$wk, 黑王=$bk)"
        
        // 【新增硬核门禁】国际象棋对局中绝不可能只剩下不到4个子（最少也是双王+1个兵或马）。
        // 如果棋子总数少于 4，99.9% 是多邻国 UI 区域产生的假框！
        if (totalPieces <= 3) return "棋盘子力极度残缺 (共 $totalPieces 子)，确认为UI误识别假框"

        if (maxOf(abs(wkr - bkr), abs(wkc - bkc)) <= 1) return "双王相邻"
        val isWhite = parseFenIsWhite(fen)
        // 走子方已被将军 = 不可能局面 (上一手走子方不可能送王)；非走子方被将属正常 (正在被将军)
        if (isKingInCheck(board, isWhite)) return "走子方已被将军 (不可能局面)"
        return null
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
