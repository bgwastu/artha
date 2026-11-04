package net.wastu.artha.ui.bridge

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Live camera QR scanner. [onQrScanned] returns whether it accepted the code; a rejected
 * code (someone else's QR) resumes scanning after a short pause.
 */
@Composable
fun CameraQrScanner(
    onQrScanned: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    if (!hasCameraPermission) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Allow the camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Artha uses it only to read the pairing code that Artha Bridge shows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Allow camera")
                }
            }
        }
    } else {
        CameraViewfinder(
            onQrScanned = onQrScanned,
            modifier = modifier,
        )
    }
}

@Composable
private fun CameraViewfinder(
    onQrScanned: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var scanned by remember { mutableStateOf(false) }

    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val scanTransition = rememberInfiniteTransition(label = "scanner_sweep")
    val sweepProgress by scanTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep_progress",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!scanned) {
                            val code = decodeQr(imageProxy, reader)
                            if (code != null) {
                                scanned = true
                                previewView.post {
                                    if (!onQrScanned(code)) {
                                        previewView.postDelayed({ scanned = false }, RETRY_AFTER_REJECT_MS)
                                    }
                                }
                            }
                        }
                        imageProxy.close()
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis,
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Viewfinder overlay. It needs its own layer, or clearing the hole would also
        // clear the camera preview underneath.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val boxSize = size.minDimension * 0.72f
            val left = (size.width - boxSize) / 2f
            val top = (size.height - boxSize) / 2f
            val cornerLength = 28.dp.toPx()
            val strokeW = 4.dp.toPx()

            // Dim outer area
            drawRect(
                color = Color.Black.copy(alpha = 0.45f),
                size = size,
            )

            // Transparent center box with border
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(16.dp.toPx()),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
            )

            // Reticle corners
            val reticleColor = primaryColor
            // Top-left corner
            drawLine(reticleColor, Offset(left, top + cornerLength), Offset(left, top), strokeW)
            drawLine(reticleColor, Offset(left, top), Offset(left + cornerLength, top), strokeW)

            // Top-right corner
            drawLine(reticleColor, Offset(left + boxSize - cornerLength, top), Offset(left + boxSize, top), strokeW)
            drawLine(reticleColor, Offset(left + boxSize, top), Offset(left + boxSize, top + cornerLength), strokeW)

            // Bottom-left corner
            drawLine(reticleColor, Offset(left, top + boxSize - cornerLength), Offset(left, top + boxSize), strokeW)
            drawLine(reticleColor, Offset(left, top + boxSize), Offset(left + cornerLength, top + boxSize), strokeW)

            // Bottom-right corner
            drawLine(reticleColor, Offset(left + boxSize - cornerLength, top + boxSize), Offset(left + boxSize, top + boxSize), strokeW)
            drawLine(reticleColor, Offset(left + boxSize, top + boxSize - cornerLength), Offset(left + boxSize, top + boxSize), strokeW)

            // Scanning sweep line
            val sweepY = top + (boxSize * sweepProgress)
            drawLine(
                color = reticleColor.copy(alpha = 0.85f),
                start = Offset(left + 8.dp.toPx(), sweepY),
                end = Offset(left + boxSize - 8.dp.toPx(), sweepY),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

private fun decodeQr(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    val plane = imageProxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val width = imageProxy.width
    val height = imageProxy.height
    // Rows can be padded past the image width, so the buffer is rowStride wide.
    val rowStride = plane.rowStride

    return try {
        val source = PlanarYUVLuminanceSource(
            bytes, rowStride, height, 0, 0, width, height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decodeWithState(bitmap)
        result.text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

private const val RETRY_AFTER_REJECT_MS = 1_500L
