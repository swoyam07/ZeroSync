package com.zerosync.app.files

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.gallery.Thumbnail
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One EMU (PowerPoint unit) per point = 12700. */
private const val EMU_PER_PT = 12700f

/** Full-screen viewer for an opened file. Back arrow (or system back) returns to the list. */
@Composable
fun DocViewer(doc: DocFile, content: DocContent, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (content) {
            DocContent.Loading -> Message("Opening…")
            is DocContent.Failed -> Message(content.message)
            is DocContent.Pdf -> PdfView(content.uri, content.startPage, content.book)
            is DocContent.Word -> WordView(content.blocks)
            is DocContent.Slides -> SlidesView(content.presentation)
            is DocContent.Excel -> ExcelView(content.sheets)
            is DocContent.Text -> TextView(content.lines)
            is DocContent.Picture -> Zoomable {
                // Big system thumbnail (up to 2048 px) is sharp on screen and light on memory.
                Thumbnail(content.uri, Modifier.fillMaxSize(), sizePx = 2048, contentScale = ContentScale.Fit, placeholder = Color.Black)
            }
            is DocContent.Video -> VideoView(content.uri)
        }
        // Small back button + name, top-left, over the content.
        Row(
            Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            Text(
                doc.name,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).width(220.dp),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = ZsColors.OnSurface)
    }
}

/**
 * Two-finger pinch to zoom (1x..4x) and slide sideways while zoomed.
 * One finger is left alone, so normal scrolling still works.
 */
private fun Modifier.pinchZoom(scale: () -> Float, offsetX: () -> Float, onChange: (Float, Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val s = (scale() * event.calculateZoom()).coerceIn(1f, 4f)
                    val maxX = size.width * (s - 1f) / 2f
                    onChange(s, (offsetX() + event.calculatePan().x).coerceIn(-maxX, maxX))
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

@Composable
private fun Zoomable(content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pinchZoom({ scale }, { offsetX }) { s, x -> scale = s; offsetX = x }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX },
    ) { content() }
}

// ---------- PDF: exact pages with Android's built-in PdfRenderer ----------

@Composable
/**
 * [book] = opened from Books: starts at [startPage]; double tap a page = save it as the place
 * to reopen (only remembered by ZeroSync, the PDF is not changed).
 */
private fun PdfView(uri: Uri, startPage: Int = 0, book: Boolean = false) {
    val context = LocalContext.current
    val lock = remember { Mutex() } // PdfRenderer can draw only one page at a time
    val renderer by produceState<PdfRenderer?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.let { PdfRenderer(it) } }.getOrNull()
        }
        awaitDispose { value?.let { r -> runCatching { r.close() } } }
    }
    val pdf = renderer ?: return Message("Opening…")
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, (pdf.pageCount - 1).coerceAtLeast(0)))
    var savedPage by remember { mutableIntStateOf(if (book) startPage else -1) }
    var toast by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    toast?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(1500); toast = null } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Draw pages 1.5x the screen width so zooming stays sharp (capped to save memory).
        val targetWidth = (constraints.maxWidth * 1.5f).toInt().coerceAtMost(2000)
        Zoomable {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 64.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pdf.pageCount) { index ->
                    val page by produceState<Bitmap?>(null, index) {
                        value = withContext(Dispatchers.IO) {
                            lock.withLock {
                                runCatching {
                                    pdf.openPage(index).use { p ->
                                        val h = (targetWidth.toFloat() * p.height / p.width).toInt()
                                        Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888).also { bmp ->
                                            bmp.eraseColor(android.graphics.Color.WHITE)
                                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        }
                                    }
                                }.getOrNull()
                            }
                        }
                    }
                    val bmp = page
                    if (bmp == null) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f / 1.414f).background(ZsColors.Surface))
                    } else {
                        Box {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().then(
                                    if (book) Modifier.pointerInput(index) {
                                        detectTapGestures(onDoubleTap = {
                                            Books.savePage(context, uri, index)
                                            savedPage = index
                                            toast = "Page ${index + 1} saved"
                                        })
                                    } else Modifier,
                                ),
                            )
                            // Small "saved" mark on the saved page.
                            if (book && index == savedPage) {
                                Text("Saved", color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50))
                                        .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        toast?.let {
            Text(it, color = Color.Black, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

// ---------- Word ----------

@Composable
private fun WordView(blocks: List<DocBlock>) {
    Zoomable {
        LazyColumn(
            contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(blocks) { _, block ->
                when (block) {
                    is DocParagraph -> Paragraph(block)
                    is DocImage -> BytesImage(block.bytes, Modifier.fillMaxWidth())
                    is DocTable -> TableView(block.rows)
                }
            }
        }
    }
}

@Composable
private fun Paragraph(p: DocParagraph) {
    val style = when (p.heading) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    val text = buildAnnotatedString {
        if (p.bullet) append("•  ")
        p.runs.forEach { run ->
            withStyle(
                SpanStyle(
                    fontWeight = if (run.bold || p.heading > 0) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                ),
            ) { append(run.text) }
        }
    }
    Text(text, style = style, color = ZsColors.OnSurface, modifier = Modifier.padding(start = if (p.bullet) 8.dp else 0.dp))
}

@Composable
private fun TableView(rows: List<List<String>>) {
    val cols = rows.maxOfOrNull { it.size } ?: 0
    Column(Modifier.horizontalScroll(rememberScrollState()).border(BorderStroke(1.dp, ZsColors.Secondary))) {
        rows.forEach { row ->
            Row {
                repeat(cols) { c ->
                    Text(
                        row.getOrElse(c) { "" },
                        color = ZsColors.OnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .width(140.dp)
                            .border(BorderStroke(0.5.dp, ZsColors.Secondary.copy(alpha = 0.6f)))
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

/** Picture from the file, decoded small enough for the screen, off the main thread. */
@Composable
private fun BytesImage(bytes: ByteArray, modifier: Modifier, scale: ContentScale = ContentScale.FillWidth) {
    val bitmap by produceState<Bitmap?>(null, bytes) {
        value = withContext(Dispatchers.IO) { decodeSampled(bytes, 1400) }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = scale, modifier = modifier) }
}

private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null // e.g. EMF/WMF pictures Android can't draw
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

// ---------- PowerPoint: sideways (fixed landscape), one slide per screen ----------

@Composable
private fun SlidesView(pres: Presentation) {
    // Only exception to portrait-only: turn the screen sideways (fixed landscape, NOT the tilt
    // sensor / auto-rotate) while a slideshow is open, and put it back to portrait after.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = before }
    }
    if (pres.slides.isEmpty()) return Message("No slides")

    val pager = rememberPagerState { pres.slides.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { i ->
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                SlideView(pres, pres.slides[i])
            }
        }
        Text(
            "${pager.currentPage + 1} / ${pres.slides.size}",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
    }
}

@Composable
private fun SlideView(pres: Presentation, slide: Slide) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .aspectRatio(pres.width.toFloat() / pres.height, matchHeightConstraintsFirst = true)
            .background(Color.White), // slides are documents: white page like in PowerPoint
    ) {
        val scale = constraints.maxWidth.toFloat() / pres.width // px per EMU
        slide.items.forEach { item ->
            val box = Modifier
                .offset { IntOffset((item.x * scale).toInt(), (item.y * scale).toInt()) }
                .size(with(density) { (item.w * scale).toDp() }, with(density) { (item.h * scale).toDp() })
            when (item) {
                is SlidePicture -> BytesImage(item.bytes, box, ContentScale.FillBounds)
                is SlideTextBox -> {
                    val text = buildAnnotatedString {
                        item.paragraphs.forEachIndexed { n, para ->
                            if (n > 0) append("\n")
                            para.forEach { run ->
                                val pt = if (run.sizePt > 0f) run.sizePt else if (item.isTitle) 36f else 18f
                                withStyle(
                                    SpanStyle(
                                        fontSize = with(density) { (pt * EMU_PER_PT * scale).toSp() },
                                        fontWeight = if (run.bold || item.isTitle) FontWeight.Bold else null,
                                        color = run.color?.let { Color(it) } ?: Color.Black,
                                    ),
                                ) { append(run.text) }
                            }
                        }
                    }
                    Text(text, modifier = box, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

// ---------- Excel ----------

@Composable
private fun ExcelView(sheets: List<Sheet>) {
    if (sheets.isEmpty()) return Message("Empty workbook")
    var current by remember { mutableIntStateOf(0) }
    val sheet = sheets[current.coerceIn(0, sheets.lastIndex)]
    val cols = sheet.rows.maxOfOrNull { it.size } ?: 0
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp)) {
        // Sheet tabs.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sheets.forEachIndexed { i, s ->
                val active = i == current
                Text(
                    s.name,
                    color = if (active) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Color.White else ZsColors.Surface)
                        .clickable { current = i }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        // Grid: one sideways scroll for all rows, rows load lazily as you scroll down.
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(CELL_WIDTH * (cols + 1)), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(sheet.rows) { r, row ->
                    Row {
                        Cell("${r + 1}", header = true)
                        repeat(cols) { c -> Cell(row.getOrElse(c) { "" }) }
                    }
                }
            }
        }
    }
}

private val CELL_WIDTH = 110.dp

@Composable
private fun Cell(text: String, header: Boolean = false) {
    Text(
        text,
        color = if (header) ZsColors.Secondary else ZsColors.OnSurface,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(CELL_WIDTH)
            .border(BorderStroke(0.5.dp, ZsColors.Secondary.copy(alpha = 0.5f)))
            .padding(6.dp),
    )
}

// ---------- Video: opens paused, user presses play ----------

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoView(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = false // no autoplay
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true; showController() } },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---------- Text / CSV ----------

@Composable
private fun TextView(lines: List<String>) {
    Zoomable {
        LazyColumn(contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp, start = 12.dp, end = 12.dp)) {
            itemsIndexed(lines) { _, line ->
                Text(line, color = ZsColors.OnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}
