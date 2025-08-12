package com.example.ffmpegcomposeal7one.ffmpeg

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import com.arthenica.ffmpegkit.SessionState
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ExtractionResult(
    val success: Boolean,
    val message: String,
    val logs: List<String> = emptyList()
)

object AudioExtractor {

    private const val TAG: String = "AudioExtractor"

    private fun buildCommand(
        inputPath: String,
        outputPath: String,
        formatExtension: String,
        bitrateKbps: Int?
    ): String {
        val args = mutableListOf(
            "-y",
            "-i", inputPath,
            "-vn"
        )

        if (bitrateKbps != null && bitrateKbps > 0) {
            args.addAll(listOf("-b:a", "${bitrateKbps}k"))
        } else if (formatExtension.equals("mp3", ignoreCase = true)) {
            args.addAll(listOf("-q:a", "2"))
        }

        when (formatExtension.lowercase()) {
            "aac", "m4a" -> args.addAll(listOf("-c:a", "aac"))
            "wav" -> args.addAll(listOf("-c:a", "pcm_s16le"))
            "flac" -> args.addAll(listOf("-c:a", "flac"))
            "opus" -> args.addAll(listOf("-c:a", "libopus"))
            "mp3" -> {
                if (supportsEncoder("libmp3lame")) {
                    args.addAll(listOf("-c:a", "libmp3lame"))
                } else {
                    Log.w(TAG, "MP3 encoder libmp3lame not present in this build; command will likely fail")
                    // Leave codec unset to let ffmpeg fail with a clear error; ViewModel will surface logs.
                }
            }
            else -> {}
        }

        args.add(outputPath)
        return args.joinToString(separator = " ")
    }

    suspend fun extract(
        inputPath: String,
        outputPath: String,
        formatExtension: String,
        bitrateKbps: Int?
    ): ExtractionResult = suspendCancellableCoroutine { cont ->
        // Early capability validation for known external encoders
        if (formatExtension.equals("mp3", ignoreCase = true) && !supportsEncoder("libmp3lame")) {
            val message = "MP3 encoder (libmp3lame) not available in this AAR. Choose AAC/M4A or rebuild with --full --enable-gpl."
            Log.e(TAG, message)
            cont.resume(ExtractionResult(success = false, message = message, logs = emptyList()))
            return@suspendCancellableCoroutine
        }

        val collectedLogs = mutableListOf<String>()
        val command = buildCommand(inputPath, outputPath, formatExtension, bitrateKbps)
        Log.i(TAG, "FFmpeg start | input=$inputPath | output=$outputPath | fmt=$formatExtension | br=$bitrateKbps")
        Log.d(TAG, "Command: $command")
        val logCallback = LogCallback { message ->
            if (message.level == Level.AV_LOG_INFO || message.level == Level.AV_LOG_WARNING || message.level == Level.AV_LOG_ERROR) {
                collectedLogs.add(message.message)
                when (message.level) {
                    Level.AV_LOG_ERROR -> Log.e(TAG, message.message)
                    Level.AV_LOG_WARNING -> Log.w(TAG, message.message)
                    else -> Log.i(TAG, message.message)
                }
            }
        }

        // Forward native logs to callbacks
        try { FFmpegKitConfig.enableRedirection() } catch (_: Throwable) {}

        val t0 = System.nanoTime()
        val statisticsCallback = StatisticsCallback { s: Statistics ->
            Log.d(TAG, "stats: time=${s.time} size=${s.size} bitrate=${s.bitrate} speed=${s.speed}")
        }

        val session = FFmpegKit.executeAsync(command, { newSession: Session ->
            val state = newSession.state
            val code = newSession.returnCode
            val success = state == SessionState.COMPLETED && ReturnCode.isSuccess(code)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val msg = if (success) "Extraction completed in ${elapsedMs}ms" else "Failed: ${code?.value} after ${elapsedMs}ms"
            Log.i(TAG, "FFmpeg done | state=$state | code=$code | success=$success | elapsedMs=$elapsedMs")
            if (!cont.isCompleted) {
                cont.resume(
                    ExtractionResult(
                        success = success,
                        message = msg,
                        logs = collectedLogs.toList()
                    )
                )
            }
        }, logCallback, statisticsCallback)

        cont.invokeOnCancellation {
            try {
                session.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "Cancellation error", t)
            }
        }
    }

    private fun supportsEncoder(name: String): Boolean {
        return try {
            val session = FFmpegKit.execute("-hide_banner -h encoder=$name")
            ReturnCode.isSuccess(session.returnCode)
        } catch (t: Throwable) {
            false
        }
    }
}


