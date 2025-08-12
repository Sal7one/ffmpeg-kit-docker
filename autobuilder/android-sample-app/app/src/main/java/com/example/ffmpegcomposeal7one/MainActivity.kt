package com.example.ffmpegcomposeal7one

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ffmpegcomposeal7one.ui.ExtractorViewModel
import com.example.ffmpegcomposeal7one.ui.theme.Ffmpegcomposeal7oneTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ffmpegcomposeal7one.ffmpeg.FfmpegSelfTest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ffmpegcomposeal7oneTheme {
                App()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(viewModel: ExtractorViewModel = viewModel()) {
    val context = LocalContext.current
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val state by viewModel.uiState.collectAsState()
    val pickVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        viewModel.onPickVideo(uri, context.contentResolver)
        val msg = if (uri != null) "Picked video: $uri" else "Video selection cancelled"
        scope.launch { snackHost.showSnackbar(msg) }
    }
    val pickFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Some providers may not allow persist; ignore
            }
        }
        viewModel.onPickOutputTree(uri)
        val msg = if (uri != null) "Picked folder: $uri" else "Folder selection cancelled"
        scope.launch { snackHost.showSnackbar(msg) }
    }

    LaunchedEffect(Unit) {
        // Show any errors as snackbars
        launch {
            viewModel.uiState.collect { state ->
                state.errorMessage?.let { msg ->
                    snackHost.showSnackbar(msg)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("FFmpeg Audio Extractor", fontWeight = FontWeight.SemiBold) })
        },
        snackbarHost = { SnackbarHost(snackHost) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val ctx = LocalContext.current
            var selfTestResult by remember { mutableStateOf<String?>(null) }

            Button(
                onClick = {
                    scope.launch {
                        selfTestResult = "Running…"
                        val result = withContext(Dispatchers.IO) {
                            FfmpegSelfTest.runAll(ctx)
                        }
                        selfTestResult = result
                        Log.e("TestResult", "$selfTestResult")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Run FFmpeg self-test") }

            selfTestResult?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1) Pick a video", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickVideoLauncher.launch("video/*") }) { Text("Choose Video") }
                        Text(state.pickedVideoName ?: "No file selected")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2) Output folder", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickFolderLauncher.launch(state.outputTreeUri) }) { Text("Choose Folder") }
                        Text(state.outputTreeUri?.toString() ?: "Not set")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("3) Output settings", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        val formats = listOf("mp3", "aac", "m4a", "wav", "flac", "opus")
                        var expanded by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = state.preferredExtension,
                            onValueChange = {},
                            label = { Text("Format") },
                            readOnly = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            val idx = formats.indexOf(state.preferredExtension).coerceAtLeast(0)
                            val next = formats[(idx + 1) % formats.size]
                            viewModel.onChangeExtension(next)
                        }) { Text("Change") }
                    }
                    Column {
                        Text("Bitrate: ${state.preferredBitrateKbps} kbps")
                        Slider(
                            value = state.preferredBitrateKbps.toFloat(),
                            onValueChange = { viewModel.onChangeBitrateKbps(it.toInt()) },
                            valueRange = 64f..320f,
                            steps = 320 - 64 - 1
                        )
                    }
                }
            }

            Button(
                onClick = { viewModel.extract(context.contentResolver) },
                enabled = !state.isRunning && state.pickedVideoUri != null && state.outputTreeUri != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.isRunning) "Working…" else "Extract Audio") }

            if (state.isRunning) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                state.progressMessage?.let { Text(it) }
            }

            if (state.lastOutputUri != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Last output", style = MaterialTheme.typography.titleMedium)
                        Text(state.lastOutputUri.toString())
                        AudioPlayer(uri = state.lastOutputUri)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/*"
                                    putExtra(Intent.EXTRA_STREAM, state.lastOutputUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Share audio"))
                            }) { Text("Share") }
                            OutlinedButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(state.lastOutputUri, "audio/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            }) { Text("Open") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Tip: long-press the app icon → App info → Permissions if you need to adjust storage access.")
        }
    }
}

@Composable
private fun AudioPlayer(uri: Uri?) {
    if (uri == null) return
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(uri) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
            }
        }
    )
}
