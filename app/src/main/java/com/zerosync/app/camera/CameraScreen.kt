package com.zerosync.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zerosync.app.ui.PillSwitch
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

// Room at the bottom so controls sit above the nav strip.
private val NavClearance = 88.dp

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCamera(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LifecycleResumeEffect(Unit) {
        granted = hasCamera(context)
        vm.refresh()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            vm.mode == CameraMode.Saved -> SavedList(vm)
            !granted -> {
                PermissionPrompt { launcher.launch(Manifest.permission.CAMERA) }
                // Saved files stay reachable without camera permission (same spot as in camera view).
                SavedButton(
                    vm,
                    Modifier.align(Alignment.BottomEnd).padding(bottom = NavClearance, end = 56.dp),
                )
            }
            else -> CameraView(vm)
        }
        // Modes are picked in the panel under the frame (CameraView). Saved: back arrow top-left.
        if (vm.mode == CameraMode.Saved) {
            androidx.activity.compose.BackHandler { vm.mode = vm.lastCameraMode }
            Icon(
                ZsIcons.Back, contentDescription = "Back to camera", tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).size(40.dp)
                    .clip(CircleShape).clickable { vm.mode = vm.lastCameraMode }.padding(8.dp),
            )
        }
    }

    // PDF: after each page, ask whether to add another.
    if (vm.askAnotherPage) {
        AlertDialog(
            onDismissRequest = vm::addAnotherPage,
            containerColor = ZsColors.Surface,
            title = { Text("Add another page?", color = Color.White) },
            text = { Text("${vm.pdfPages.size} page(s) so far.", color = ZsColors.OnSurface) },
            confirmButton = { TextButton(onClick = vm::addAnotherPage) { Text("Yes", color = Color.White) } },
            dismissButton = { TextButton(onClick = vm::finishPdf) { Text("No, save PDF", color = ZsColors.Secondary) } },
        )
    }

    // PDF name: shown after "No" / "Submit". Saved as "<name>.pdf". Cancel = back to the camera.
    vm.pdfNameDraft?.let { draft ->
        var name by remember(draft) { mutableStateOf(draft) }
        AlertDialog(
            onDismissRequest = { vm.pdfNameDraft = null },
            containerColor = ZsColors.Surface,
            title = { Text("Name this PDF", color = Color.White) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    suffix = { Text(".pdf", color = ZsColors.Secondary) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White, unfocusedBorderColor = ZsColors.Secondary, cursorColor = Color.White,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.savePdf(name) },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { vm.pdfNameDraft = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }

    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            delay(1800)
            vm.message = null
        }
    }
}

/** Live camera with the document frame. Only the inside of the frame is saved. */
@Composable
private fun CameraView(vm: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // Preview and photo both 4:3, so what you see matches what is captured.
    val selector = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val capture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(selector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    // Camera runs only while this screen is shown (CameraX also stops it in the background).
    // QR mode adds a frame reader (ZXing, offline) instead of the photo capture.
    val qrMode = vm.mode == CameraMode.Qr
    DisposableEffect(lifecycleOwner, qrMode) {
        val future = ProcessCameraProvider.getInstance(context)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            if (qrMode) {
                val analysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val reader = com.google.zxing.MultiFormatReader().apply {
                    setHints(mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true))
                }
                analysis.setAnalyzer(worker) { image ->
                    // Paused while a result is on screen.
                    if (vm.qrResult == null) {
                        decodeFrame(reader, image)?.let { r ->
                            val info = checkQr(r)
                            previewView.post { if (vm.qrResult == null) vm.qrResult = info }
                        }
                    }
                    image.close()
                }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } else {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            worker.shutdown()
        }
    }
    vm.qrResult?.let { QrResultSheet(it, onClose = { vm.qrResult = null }) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val frame = frameRect(viewW, viewH, vm.mode.aspect)

        AndroidView({ previewView }, Modifier.fillMaxSize())
        FrameOverlay(frame)

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = NavClearance),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            vm.message?.let { Text(it, color = Color.White, modifier = Modifier.padding(bottom = 12.dp)) }
            // Mode panel (Passport · ID · PDF) under the frame: tap, or swipe left/right inside it.
            ModePanel(vm.mode, onSelect = { vm.mode = it }, Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
            // PDF in progress: Cancel and Submit in a row above the shutter.
            if (vm.mode == CameraMode.Pdf && vm.pdfPages.isNotEmpty()) {
                Row(
                    Modifier.padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TextButton(onClick = vm::cancelPdf) { Text("Cancel", color = ZsColors.Secondary) }
                    Button(
                        onClick = vm::finishPdf,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) { Text("Submit ${vm.pdfPages.size}") }
                }
            }
            // Shutter exactly in the centre; Saved folder at the right edge.
            Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                if (!qrMode) Shutter(enabled = !vm.busy) {
                    capture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val cropped = cropToFrame(image, viewW, viewH, frame)
                                image.close()
                                vm.onCaptured(cropped)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                vm.message = "Capture failed"
                            }
                        },
                    )
                }
                SavedButton(vm, Modifier.align(Alignment.CenterEnd).padding(end = 28.dp)) // a bit in from the edge
            }
        }
    }
}

private val CameraModes = listOf(CameraMode.Passport, CameraMode.Id, CameraMode.Pdf, CameraMode.Qr)

/**
 * Frosted dark panel with the 3 document modes (round icon + small caps label).
 * Selected = white circle with black icon and white label. Tap one, or swipe sideways
 * inside the panel: left = next mode, right = previous.
 */
@Composable
private fun ModePanel(selected: CameraMode, onSelect: (CameraMode) -> Unit, modifier: Modifier = Modifier) {
    val index = CameraModes.indexOf(selected).coerceAtLeast(0)
    val currentIndex by androidx.compose.runtime.rememberUpdatedState(index)
    val currentOnSelect by androidx.compose.runtime.rememberUpdatedState(onSelect)
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xCC1C1C1C))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        val limit = 40.dp.toPx()
                        val next = when {
                            dx < -limit -> currentIndex + 1
                            dx > limit -> currentIndex - 1
                            else -> currentIndex
                        }.coerceIn(0, CameraModes.lastIndex)
                        if (next != currentIndex) currentOnSelect(CameraModes[next])
                    },
                ) { change, amount -> change.consume(); dx += amount }
            }
            .padding(vertical = 14.dp),
    ) {
        CameraModes.forEach { mode ->
            val active = mode == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(mode) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (active) Color.White else Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when (mode) {
                        CameraMode.Passport -> ZsIcons.Contact
                        CameraMode.Id -> ZsIcons.Badge
                        CameraMode.Qr -> ZsIcons.Qr
                        else -> ZsIcons.Pdf
                    }
                    Icon(icon, contentDescription = null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                }
                Text(
                    mode.label.uppercase(),
                    color = if (active) Color.White else ZsColors.Secondary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Folder button that opens Saved (your private photos / PDFs). */
@Composable
private fun SavedButton(vm: CameraViewModel, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, Color.White, RoundedCornerShape(14.dp))
            .clickable { vm.openSaved() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(ZsIcons.Folder, contentDescription = "Saved", tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Frame in screen pixels: centred, 84% wide (or shorter if it would be too tall). */
private fun frameRect(viewW: Float, viewH: Float, aspect: Float): Rect {
    var w = viewW * 0.84f
    var h = w / aspect
    val maxH = viewH * 0.5f // leaves room for the mode panel + shutter below
    if (h > maxH) {
        h = maxH
        w = h * aspect
    }
    val left = (viewW - w) / 2f
    val top = viewH * 0.38f - h / 2f // centered in the upper part, above the controls
    return Rect(left, top, left + w, top + h)
}

/** Darken everything outside the frame and draw a white border around it. */
@Composable
private fun FrameOverlay(frame: Rect) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawRect(Color.Black.copy(alpha = 0.6f))
        val corner = CornerRadius(12.dp.toPx())
        // Punch a clear hole where the document goes.
        drawRoundRect(Color.Transparent, frame.topLeft, frame.size, corner, blendMode = BlendMode.Clear)
        drawRoundRect(Color.White, frame.topLeft, frame.size, corner, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else ZsColors.Secondary)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * Turn the camera photo upright, then cut out exactly what was inside the frame on screen.
 * The preview fills the screen (FILL_CENTER), so map screen -> photo with the same scale.
 */
private fun cropToFrame(image: ImageProxy, viewW: Float, viewH: Float, frame: Rect): Bitmap {
    val raw = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees
    val upright = if (rotation == 0) raw else {
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { raw.recycle() }
    }
    val scale = maxOf(viewW / upright.width, viewH / upright.height)
    val offX = (viewW - upright.width * scale) / 2f
    val offY = (viewH - upright.height * scale) / 2f
    val x = ((frame.left - offX) / scale).toInt().coerceIn(0, upright.width - 1)
    val y = ((frame.top - offY) / scale).toInt().coerceIn(0, upright.height - 1)
    val w = (frame.width / scale).toInt().coerceIn(1, upright.width - x)
    val h = (frame.height / scale).toInt().coerceIn(1, upright.height - y)
    return Bitmap.createBitmap(upright, x, y, w, h).also { if (it !== upright) upright.recycle() }
}

/** Your captures. Private by default; the switch makes one visible to other apps (and back). */
@Composable
private fun SavedList(vm: CameraViewModel) {
    // Category picker: icon column at the bottom-right (like the File section), no counts.
    val categories = listOf(CameraMode.Passport, CameraMode.Id, CameraMode.Pdf)
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CameraMode.Passport) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp, end = 64.dp)) {
            SavedItems(vm, vm.saved.filter { it.kind == category.label.uppercase() })
        }
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = NavClearance + 40.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.92f))
                .border(1.dp, ZsColors.Secondary, RoundedCornerShape(16.dp))
                .padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            categories.forEach { mode ->
                val active = mode == category
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp)) // rounded square, like the File section
                        .background(if (active) Color.White else Color.Transparent)
                        .clickable { category = mode },
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when (mode) {
                        CameraMode.Passport -> ZsIcons.Contact
                        CameraMode.Id -> ZsIcons.Badge
                        CameraMode.Qr -> ZsIcons.Qr
                        else -> ZsIcons.Pdf
                    }
                    Icon(icon, contentDescription = mode.label, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun SavedItems(vm: CameraViewModel, docs: List<SavedDoc>) {
    var toDelete by remember { mutableStateOf<SavedDoc?>(null) }
    toDelete?.let { doc -> ConfirmDelete(doc, onDelete = { vm.delete(doc) }, onDismiss = { toDelete = null }) }
    if (docs.isEmpty()) {
        Text(
            "Nothing saved yet",
            color = ZsColors.OnSurface.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxSize().padding(top = 120.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = NavClearance),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Private by default. Switch on = other apps (e.g. WhatsApp) can see it. Switch off when done.",
                style = MaterialTheme.typography.bodySmall,
                color = ZsColors.OnSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(docs, key = { it.file.name }) { doc ->
            // Hold = "Delete this file?" (same as the File section). No swipe.
            run {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ZsColors.Surface)
                        .combinedClickable(onClick = {}, onLongClick = { toDelete = doc })
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DocThumb(doc)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(doc.displayName.substringBeforeLast('.'), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (doc.isPublic) "Public · " else "Private · ") +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.file.lastModified())),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (doc.isPublic) Color.White else ZsColors.Secondary,
                        )
                    }
                    Switch(
                        checked = doc.isPublic,
                        onCheckedChange = { vm.setPublic(doc, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White,
                            uncheckedThumbColor = ZsColors.Secondary,
                            uncheckedTrackColor = Color.Black,
                            uncheckedBorderColor = ZsColors.Secondary,
                        ),
                    )
                }
            }
        }
    }
}

/** Red delete: the one color exception, user-approved (danger action). */
private val DeleteRed = Color(0xFFD32F2F)

/** "Delete this file?" with a red Delete button; Cancel keeps it. */
@Composable
private fun ConfirmDelete(doc: SavedDoc, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZsColors.Surface,
        title = { Text("Delete this file?", color = Color.White) },
        text = { Text("${doc.file.name}\nIt will be removed for good.", color = ZsColors.OnSurface) },
        confirmButton = {
            Button(
                onClick = { onDelete(); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ZsColors.Secondary) } },
    )
}

/** Small preview: the photo itself, or a file icon for PDFs. Decoded small, off the main thread. */
@Composable
private fun DocThumb(doc: SavedDoc) {
    val box = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
    if (doc.isPdf) {
        Box(box, contentAlignment = Alignment.Center) {
            Icon(ZsIcons.File, contentDescription = "PDF", tint = Color.White, modifier = Modifier.size(28.dp))
        }
        return
    }
    val bitmap by produceState<Bitmap?>(null, doc.file) {
        value = withContext(Dispatchers.IO) { decodeSmall(doc.file, 160) }
    }
    Box(box) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

private fun decodeSmall(file: File, target: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ZeroSync needs the camera to take document photos. Photos stay private on this phone.", color = ZsColors.OnSurface)
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) { Text("Allow camera") }
    }
}

private fun hasCamera(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Find a code in one camera frame (brightness channel only). null = none in this frame. */
private fun decodeFrame(reader: com.google.zxing.MultiFormatReader, image: ImageProxy): com.google.zxing.Result? {
    val plane = image.planes.firstOrNull() ?: return null
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
    val height = image.height
    if (bytes.size < rowStride * (height - 1) + image.width) return null
    val source = com.google.zxing.PlanarYUVLuminanceSource(bytes, rowStride, height, 0, 0, image.width, height, false)
    return runCatching {
        reader.decodeWithState(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
    }.getOrNull().also { reader.reset() }
}

/**
 * What the code says — text only. Nothing is opened, joined, called or saved.
 * Type, readable fields (Wi-Fi password hidden until tapped), warnings, raw text, Copy / Cancel.
 */
@Composable
private fun QrResultSheet(info: QrInfo, onClose: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showSecret by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val worst = info.warnings.maxOfOrNull { it.level } ?: QrLevel.Info
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = ZsColors.Surface,
        title = {
            Column {
                Text(info.type, color = if (worst == QrLevel.Danger) Color(0xFFE53935) else Color.White)
                Text(info.format, color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
            }
        },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                info.warnings.forEach { w ->
                    val c = when (w.level) { QrLevel.Danger -> Color(0xFFE53935); QrLevel.Caution -> Color.White; QrLevel.Info -> ZsColors.Secondary }
                    Text((if (w.level == QrLevel.Info) "" else "⚠ ") + w.text, color = c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                }
                info.fields.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(label, color = ZsColors.Secondary, modifier = Modifier.weight(0.4f))
                        val secret = label == info.secretField && !showSecret
                        Text(
                            if (secret) "••••••  (tap)" else value,
                            color = Color.White,
                            fontWeight = if (label == "Real site") androidx.compose.ui.text.font.FontWeight.Bold else null,
                            modifier = Modifier.weight(0.6f).then(if (label == info.secretField) Modifier.clickable { showSecret = !showSecret } else Modifier),
                        )
                    }
                }
                Text("Full text", color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
                // Plain text only: not clickable, nothing is opened.
                // The Wi-Fi password is masked here too until the eye/tap shows it.
                val secretValue = info.secretField?.let { k -> info.fields.firstOrNull { it.first == k }?.second }
                val shownRaw = if (secretValue != null && !showSecret) info.raw.replace(secretValue, "••••••") else info.raw
                Text(shownRaw, color = Color.White, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black).padding(10.dp))
                // Wi-Fi: copy just the password (it stays hidden on screen unless tapped).
                info.secretField?.let { key ->
                    info.fields.firstOrNull { it.first == key }?.second?.let { secret ->
                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(secret)); copied = true }) {
                            Text("Copy ${key.lowercase()} only", color = Color.White)
                        }
                    }
                }
                if (copied) Text("Copied", color = ZsColors.Secondary, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(info.raw)); copied = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = ZsColors.Secondary) } },
    )
}
