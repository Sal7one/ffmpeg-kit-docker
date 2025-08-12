package com.example.ffmpegcomposeal7one.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ffmpegcomposeal7one.data.PreferencesRepository
import com.example.ffmpegcomposeal7one.ffmpeg.AudioExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExtractionUiState(
    val pickedVideoUri: Uri? = null,
    val pickedVideoName: String? = null,
    val outputTreeUri: Uri? = null,
    val preferredExtension: String = "mp3",
    val preferredBitrateKbps: Int = 192,
    val isRunning: Boolean = false,
    val progressMessage: String? = null,
    val lastOutputUri: Uri? = null,
    val lastOutputPath: String? = null,
    val errorMessage: String? = null,
)

class ExtractorViewModel(app: Application) : AndroidViewModel(app) {

    private val preferencesRepository = PreferencesRepository(app)

    private val _pickedVideoUri = MutableStateFlow<Uri?>(null)
    private val _pickedVideoName = MutableStateFlow<String?>(null)
    private val _outputTreeUri = MutableStateFlow<Uri?>(null)
    private val _preferredExtension = MutableStateFlow("mp3")
    private val _preferredBitrate = MutableStateFlow(192)
    private val _isRunning = MutableStateFlow(false)
    private val _progress = MutableStateFlow<String?>(null)
    private val _lastOutputUri = MutableStateFlow<Uri?>(null)
    private val _lastOutputPath = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ExtractionUiState> = combine(
        _pickedVideoUri,
        _pickedVideoName,
        _outputTreeUri,
        _preferredExtension,
        _preferredBitrate,
        _isRunning,
        _progress,
        _lastOutputUri,
        _lastOutputPath,
        _errorMessage
    ) { values ->
        ExtractionUiState(
            pickedVideoUri = values[0] as Uri?,
            pickedVideoName = values[1] as String?,
            outputTreeUri = values[2] as Uri?,
            preferredExtension = values[3] as String,
            preferredBitrateKbps = values[4] as Int,
            isRunning = values[5] as Boolean,
            progressMessage = values[6] as String?,
            lastOutputUri = values[7] as Uri?,
            lastOutputPath = values[8] as String?,
            errorMessage = values[9] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExtractionUiState())

    init {
        viewModelScope.launch {
            preferencesRepository.lastTreeUri.collect { uriString ->
                _outputTreeUri.value = uriString?.let { Uri.parse(it) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.lastFormat.collect { fmt ->
                if (!fmt.isNullOrBlank()) _preferredExtension.value = fmt
            }
        }

        viewModelScope.launch {
            preferencesRepository.lastBitrateKbps.collect { br ->
                if (br != null && br > 0) _preferredBitrate.value = br
            }
        }
    }

    fun onPickVideo(uri: Uri?, contentResolver: ContentResolver) {
        _pickedVideoUri.value = uri
        _pickedVideoName.value = uri?.let { queryDisplayName(contentResolver, it) }
        _errorMessage.value = if (uri == null) "Video selection cancelled" else null
    }

    fun onPickOutputTree(uri: Uri?) {
        _outputTreeUri.value = uri
        viewModelScope.launch { preferencesRepository.setLastTreeUri(uri?.toString()) }
        _errorMessage.value = if (uri == null) "Folder selection cancelled" else null
    }

    fun onChangeExtension(ext: String) {
        _preferredExtension.value = ext
        viewModelScope.launch { preferencesRepository.setLastFormat(ext) }
    }

    fun onChangeBitrateKbps(kbps: Int) {
        _preferredBitrate.value = kbps
        viewModelScope.launch { preferencesRepository.setLastBitrateKbps(kbps) }
    }

    fun extract(contentResolver: ContentResolver) {
        val inputUri = _pickedVideoUri.value ?: run {
            _errorMessage.value = "Pick a video first"
            return
        }
        val treeUri = _outputTreeUri.value ?: run {
            _errorMessage.value = "Choose an output folder"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _errorMessage.value = null
                _progress.value = "Preparing…"
                _isRunning.value = true

                // Copy input to a temp file path because FFmpegKit works best with filesystem paths
                val tempInput = copyUriToTempFile(contentResolver, inputUri)
                val outputDoc = createOutputDocument(treeUri, buildOutputFileName())
                val ext = _preferredExtension.value
                val safeExt = if (ext.equals("mp3", ignoreCase = true)) "m4a" else ext
                val tempOutput = File.createTempFile("out_", ".${safeExt}", getApplication<Application>().cacheDir)

                _progress.value = "Running FFmpeg…"
                val result = AudioExtractor.extract(
                    inputPath = tempInput.absolutePath,
                    outputPath = tempOutput.absolutePath,
                    formatExtension = if (ext.equals("mp3", true)) "m4a" else ext,
                    bitrateKbps = _preferredBitrate.value
                )

                if (!result.success) {
                    val head = result.logs.takeLast(20).joinToString(separator = "\n")
                    _errorMessage.value = result.message + (if (head.isNotBlank()) "\n\nLogs:\n$head" else "") +
                        if (ext.equals("mp3", true)) "\n\nTip: MP3 may require rebuilding FFmpegKit with --full --enable-gpl; using AAC/M4A instead." else ""
                } else {
                    _progress.value = "Writing output…"
                    writeFileToDocument(contentResolver, tempOutput, outputDoc.uri)
                    _lastOutputUri.value = outputDoc.uri
                    _lastOutputPath.value = outputDoc.uri.toString()
                }

                tempInput.delete()
                tempOutput.delete()
            } catch (t: Throwable) {
                _errorMessage.value = t.message
            } finally {
                _isRunning.value = false
                _progress.value = null
            }
        }
    }

    private fun buildOutputFileName(): String {
        val base = _pickedVideoName.value?.substringBeforeLast('.') ?: "audio"
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = _preferredExtension.value
        return "${base}_${ts}.${ext}"
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private suspend fun copyUriToTempFile(resolver: ContentResolver, uri: Uri): File = withContext(Dispatchers.IO) {
        val inputStream: InputStream = resolver.openInputStream(uri) ?: error("Cannot open input stream")
        val temp = File.createTempFile("in_", ".tmp", getApplication<Application>().cacheDir)
        inputStream.use { ins ->
            FileOutputStream(temp).use { outs ->
                ins.copyTo(outs)
            }
        }
        temp
    }

    private fun createOutputDocument(treeUri: Uri, displayName: String): DocumentFile {
        val tree = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: error("Invalid folder")
        val mime = when (_preferredExtension.value.lowercase()) {
            "mp3" -> "audio/mpeg"
            "aac", "m4a" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
        return tree.createFile(mime, displayName) ?: error("Failed to create output file")
    }

    private suspend fun writeFileToDocument(resolver: ContentResolver, source: File, destUri: Uri) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(destUri, "w").use { out ->
            requireNotNull(out) { "Cannot open destination" }
            source.inputStream().use { input ->
                input.copyTo(out)
            }
        }
    }
}


