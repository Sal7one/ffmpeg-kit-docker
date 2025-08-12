package com.example.ffmpegcomposeal7one.ffmpeg

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

object FfmpegSelfTest {
    private const val TAG = "FfmpegSelfTest"

    private fun ok(sessionCode: ReturnCode?) = sessionCode != null && ReturnCode.isSuccess(sessionCode)

    private fun hasFromLinedList(cmd: String, flag: Char, name: String): Boolean {
        val out = FFmpegKit.execute(cmd).allLogsAsString.lowercase()
        val rx = Regex("""^\s*([A-Z]+)\s+([0-9a-z_]+)\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val needle = name.lowercase()
        return rx.findAll(out).any { m ->
            val flags = m.groupValues[1].lowercase()
            val item = m.groupValues[2].lowercase()
            flags.contains(flag.lowercaseChar()) && item == needle
        }
    }

    private fun hasMuxer(name: String): Boolean = hasFromLinedList("-hide_banner -muxers", 'E', name)

    private fun hasDemuxer(name: String): Boolean = ok(ff("-hide_banner -h demuxer=$name").returnCode)

    private fun hasProtocol(name: String, forInput: Boolean = true): Boolean {
        val s = FFmpegKit.execute("-hide_banner -protocols").allLogsAsString.lowercase()
        val sectionHeader = if (forInput) "input:" else "output:"
        val start = s.indexOf(sectionHeader).takeIf { it >= 0 } ?: return false
        val end = s.indexOf("\n\n", start).let { if (it == -1) s.length else it }
        val body = s.substring(start, end)
        val rx = Regex("""^\s*$name\b""", RegexOption.MULTILINE)
        return rx.containsMatchIn(body)
    }

    private fun hasEncoder(name: String) =
        ok(ff("-hide_banner -h encoder=$name").returnCode)
    private fun hasBitstreamFilter(name: String): Boolean = ok(ff("-hide_banner -h bsf=$name").returnCode)

    private fun ff(cmd: String) = FFmpegKit.execute(cmd)

    private fun hasDecoder(name: String) = ok(ff("-hide_banner -h decoder=$name").returnCode)
    private fun hasFilter(name: String) =
        ok(ff("-hide_banner -h filter=$name").returnCode)
    private suspend fun run(cmd: String): Pair<Boolean, String> = suspendCancellableCoroutine { cont ->
        val logs = StringBuilder()
        try { FFmpegKitConfig.enableRedirection() } catch (_: Throwable) {}
        val session = FFmpegKit.executeAsync(
            cmd,
            { s -> if (cont.isActive) cont.resume(ok(s.returnCode) to logs.toString()) },
            { log -> logs.appendLine(log.message) },
            null
        )
        cont.invokeOnCancellation { runCatching { session.cancel() } }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B","KB","MB","GB")
        val i = (log10(bytes.toDouble())/log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        return String.format("%.1f %s", bytes / 1024.0.pow(i.toDouble()), units[i])
    }

    private suspend fun httpsConnectivityTest(): Pair<Boolean, String> {
        // Pick any https URL that returns *something*; it doesn't have to be a valid media stream.
        val url = "https://example.com/"  // tiny + always up
        val (ok, log) = run("""-nostdin -v verbose -rw_timeout 5000000 -timeout 5000000 -i $url -f null -""")
        // Success cases:
        // 1) Fully OK (rare for HTML).
        // 2) We reached server/TLS worked but demux failed (common): treat as PASS for connectivity.
        val connected =
            ok ||
                    log.contains("HTTP/1.", ignoreCase = true) ||
                    log.contains("Location:", ignoreCase = true) ||
                    log.contains("server:", ignoreCase = true) ||
                    log.contains("Invalid data found", ignoreCase = true) ||
                    log.contains("Protocol not on whitelist", ignoreCase = true) // means we got that far

        return (connected to log)
    }


    suspend fun runAll(context: Context): String {
        val cache = context.cacheDir.absolutePath
        val mp3  = "$cache/selftest.mp3"
        val aac  = "$cache/selftest_aac.m4a"
        val opus = "$cache/selftest_opus.opus"
        val h264 = "$cache/selftest_h264.mp4"
        val h265 = "$cache/selftest_h265.mp4"
        val webm = "$cache/selftest_vp9.webm"
        val scaled = "$cache/selftest_scaled.mp4"
        val png = "$cache/selftest_frame.png"
        val jpg = "$cache/selftest_frame.jpg"
        val overlayVid = "$cache/selftest_overlay.mp4"
        val atempoWav = "$cache/selftest_atempo.wav"

        // clean old files
        listOf(mp3,aac,opus,h264,h265,webm,scaled,png,jpg,overlayVid,atempoWav).forEach { runCatching { File(it).delete() } }

        fun mark(ok: Boolean) = if (ok) "✅" else "❌"

        val R = StringBuilder("FFmpeg self-test\n\n")

        // Capabilities (fast)
        R.append("Capabilities\n")
        val caps = listOf(
            "encoders" to listOf("libmp3lame","libx264","libx265","aac","libopus","libvpx-vp9"),
            "decoders" to listOf("mp3","h264","hevc","aac","opus","vp9"),
            "muxers"   to listOf("mp3","mp4","matroska","webm","ogg","wav","image2"),
            "demuxers" to listOf("mp3","mov","mp4","matroska","webm","ogg","wav","image2"),
            "filters"  to listOf("aresample","scale","anull","nullsrc","testsrc"),
            "protocols" to listOf("file","pipe","http","https","crypto","srt"),
            "bitstream_filters" to listOf("h264_mp4toannexb","aac_adtstoasc")
        )
        for ((kind, names) in caps) {
            R.append("$kind:\n")
            for (name in names) {
                val has = when (kind) {
                    "encoders" -> hasEncoder(name)
                    "decoders" -> hasDecoder(name)
                    "muxers"   -> hasMuxer(name)
                    "demuxers" -> hasDemuxer(name)
                    "filters"  -> hasFilter(name)
                    "protocols"-> hasProtocol(name)
                    "bitstream_filters" -> hasBitstreamFilter(name)
                    else -> false
                }
                R.append("- $name: ${mark(has)}\n")
            }
        }
        R.append('\n')

        // ---------- Audio: MP3 ----------
        if (hasEncoder("libmp3lame")) {
            val (ok, log) = run("""-y -f lavfi -i sine=frequency=440:duration=2 -vn -c:a libmp3lame -q:a 4 "$mp3"""")
            val size = if (ok) " (${humanSize(File(mp3).length())})" else ""
            R.append("MP3 encode (libmp3lame): ${mark(ok)}$size\n")
            if (!ok) Log.w(TAG, "MP3 encode failed\n${log}")
        } else R.append("MP3 encode (libmp3lame): ❌ not built\n")

        // ---------- Audio: AAC ----------
        if (hasEncoder("aac")) {
            val (ok, log) = run("""-y -f lavfi -i sine=frequency=523:duration=2 -vn -c:a aac -b:a 128k "$aac"""")
            val size = if (ok) " (${humanSize(File(aac).length())})" else ""
            R.append("AAC encode (aac): ${mark(ok)}$size\n")
            if (!ok) Log.w(TAG, "AAC encode failed\n${log}")
        } else R.append("AAC encode (aac): ❌ not built\n")

        // ---------- Audio: Opus ----------
        if (hasEncoder("libopus")) {
            val (ok, log) = run("""-y -f lavfi -i sine=frequency=660:duration=2 -vn -c:a libopus -b:a 96k "$opus"""")
            val size = if (ok) " (${humanSize(File(opus).length())})" else ""
            R.append("Opus encode (libopus): ${mark(ok)}$size\n")
            if (!ok) Log.w(TAG, "Opus encode failed\n${log}")
        } else R.append("Opus encode (libopus): ❌ not built\n")

        // ---------- Video: H.264 ----------
        if (hasEncoder("libx264") && hasMuxer("mp4")) {
            val (ok, log) = run("""-y -f lavfi -i testsrc=size=640x360:rate=24:duration=2 -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$h264"""")
            val size = if (ok) " (${humanSize(File(h264).length())})" else ""
            R.append("H.264 encode (libx264→mp4): ${mark(ok)}$size\n")
            if (!ok) Log.w(TAG, "H.264 encode failed\n${log}")
        } else R.append("H.264 encode (libx264→mp4): ❌ not built / mp4 muxer missing\n")

        // ---------- Video: H.265 ----------
        if (hasEncoder("libx265") && hasMuxer("mp4")) {
            val (ok, log) = run("""-y -f lavfi -i testsrc=size=640x360:rate=24:duration=2 -c:v libx265 -pix_fmt yuv420p -movflags +faststart "$h265"""")
            val size = if (ok) " (${humanSize(File(h265).length())})" else ""
            R.append("H.265 encode (libx265→mp4): ${mark(ok)}$size\n")
            if (!ok) Log.w(TAG, "H.265 encode failed\n${log}")
        } else R.append("H.265 encode (libx265→mp4): ❌ not built / mp4 muxer missing\n")

        // ---------- Video: VP9/WebM (optional) ----------
        if (hasEncoder("libvpx-vp9") && hasMuxer("webm")) {
            val (ok, log) = run("""-y -f lavfi -i testsrc=size=640x360:rate=24:duration=2 -c:v libvpx-vp9 -b:v 500k "$webm"""")
            val size = if (ok) " (${humanSize(File(webm).length())})" else ""
            R.append("VP9 encode (libvpx-vp9→webm): ${mark(ok)}$size\n")
            if (!ok) Log.w(TAG, "VP9 encode failed\n${log}")
        } else R.append("VP9 encode (libvpx-vp9→webm): ❌ not built / webm muxer missing\n")

        // ---------- Filter sanity (scale) ----------
        if (hasFilter("scale")) {
            val (ok, log) = run("""-y -f lavfi -i testsrc=size=320x240:rate=10:duration=2 -vf scale=160:120 -c:v libx264 -t 2 "$scaled"""")
            R.append("Filter (scale): ${mark(ok)}\n")
            if (!ok) Log.w(TAG, "Scale filter test failed\n${log}")
        } else R.append("Filter (scale): ❌ missing\n")

        // ---------- Image encoders ----------
        if (hasEncoder("png") && hasMuxer("image2")) {
            val (ok, log) = run("""-y -f lavfi -i testsrc=size=320x180:rate=1:duration=1 -frames:v 1 -c:v png "$png"""")
            R.append("Image (PNG): ${mark(ok)}\n")
            if (!ok) Log.w(TAG, "PNG encode failed\n${log}")
        } else R.append("Image (PNG): ❌ not built\n")

        if (hasEncoder("mjpeg") && hasMuxer("image2")) {
            val (ok, log) = run("""-y -f lavfi -i testsrc=size=320x180:rate=1:duration=1 -frames:v 1 -q:v 3 -c:v mjpeg "$jpg"""")
            R.append("Image (JPEG): ${mark(ok)}\n")
            if (!ok) Log.w(TAG, "JPEG encode failed\n${log}")
        } else R.append("Image (JPEG): ❌ not built\n")

        // ---------- Overlay filter (compositing) ----------
        if (hasFilter("overlay") && hasEncoder("libx264") && hasMuxer("mp4")) {
            val (ok, log) = run("""-y -f lavfi -i color=red:s=320x240:d=1 -f lavfi -i testsrc=size=320x240:rate=24:duration=1 -filter_complex overlay=10:10 -c:v libx264 -pix_fmt yuv420p "$overlayVid"""")
            R.append("Filter (overlay): ${mark(ok)}\n")
            if (!ok) Log.w(TAG, "Overlay test failed\n${log}")
        } else R.append("Filter (overlay): ❌ missing / encoder or muxer missing\n")

        // ---------- Audio atempo filter ----------
        if (hasFilter("atempo") && hasMuxer("wav")) {
            val (ok, log) = run("""-y -f lavfi -i sine=frequency=440:duration=2 -af atempo=1.5 -c:a pcm_s16le "$atempoWav"""")
            R.append("Audio filter (atempo): ${mark(ok)}\n")
            if (!ok) Log.w(TAG, "Atempo test failed\n${log}")
        } else R.append("Audio filter (atempo): ❌ missing / wav muxer missing\n")

        // ---------- HTTPS input probe ----------
        if (hasProtocol("https")) {
            val (connected, log) = httpsConnectivityTest()
            R.append("Protocol (https): ${mark(connected)}\n")
            if (!connected) Log.w(TAG, "HTTPS connectivity check failed\n${log}")
        } else R.append("Protocol (https): ❌ not built\n")

        // ---------- SRT protocol presence (informational) ----------
        R.append("Protocol (srt): ").append(if (hasProtocol("srt")) "✅" else "❌").append('\n')

        // ---------- Decode / seek sanity ----------
        if (File(mp3).exists()) {
            val (ok, log) = run("""-v error -i "$mp3" -t 0.1 -f null -""")
            R.append("Decode (mp3): ${mark(ok)}\n")
            if (!ok) Log.w(TAG, "MP3 decode failed\n${log}")
        }
        if (File(h264).exists()) {
            val (ok, log) = run("""-v error -ss 1 -i "$h264" -frames:v 1 -f null -""")
            R.append("Seek (h264): ${mark(ok)}\n")
            if (!ok) Log.w(TAG, "H.264 seek failed\n${log}")
        }

        val version = FFmpegKitConfig.getFFmpegVersion()
        R.append("\nffmpeg version: ").append(version ?: "unknown").append('\n')

        Log.i(TAG, "\n$R")
        return R.toString()
    }
}
