package com.medlenx.lab.ui.screens.scan

import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX owns only its two use cases; closing the dialog releases them. */
@Composable
fun CameraViewfinder(onClose: () -> Unit, onCaptured: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val main = remember(context) { ContextCompat.getMainExecutor(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val active = remember { AtomicBoolean(true) }
    val previewView = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val preview = remember { Preview.Builder().build() }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(owner, previewView) {
        active.set(true)
        val bindingActive = AtomicBoolean(true)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            if (bindingActive.get()) {
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    ready = true
                } catch (e: Exception) {
                    error = "Camera unavailable: ${e.message ?: "check camera access"}"
                }
            }
        }, main)
        onDispose {
            bindingActive.set(false)
            active.set(false)
            provider?.unbind(preview, capture)
        }
    }
    DisposableEffect(executor) {
        onDispose { executor.shutdown() }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // This is a framing guide only, not a detected document boundary.
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.84f).height(390.dp)
                .border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(16.dp)))
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White)
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: "Align the prescription within the guide", color = Color.White)
                Button(enabled = ready && !busy, modifier = Modifier.padding(top = 12.dp).width(180.dp), onClick = {
                    busy = true
                    error = null
                    capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    try {
                        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val prepared = try {
                                    if (image.format != ImageFormat.JPEG) null else {
                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                                            val rotation = image.imageInfo.rotationDegrees
                                            if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                                                Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== bitmap) bitmap.recycle() }
                                        }
                                    }
                                } catch (_: OutOfMemoryError) { null }
                                catch (_: Exception) { null } finally { image.close() }
                                main.execute {
                                    if (active.get()) {
                                        busy = false
                                        if (prepared == null) error = "Could not process the photo. Please try again."
                                        else onCaptured(prepared)
                                    }
                                }
                            }
                            override fun onError(exception: ImageCaptureException) {
                                main.execute { if (active.get()) { busy = false; error = "Capture failed. Please try again." } }
                            }
                        })
                    } catch (_: Exception) {
                        busy = false
                        error = "Capture failed. Please try again."
                    }
                }) { Text(if (busy) "Capturing…" else "Capture") }
            }
        }
    }
}
