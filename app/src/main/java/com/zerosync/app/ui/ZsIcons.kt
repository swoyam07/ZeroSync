package com.zerosync.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Hand-written Material icon paths, so we avoid the large material-icons-extended library.
object ZsIcons {
    val Gallery by lazy { icon("Gallery", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z") }
    val Contact by lazy { icon("Contact", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z") }
    val Music by lazy { icon("Music", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z") }
    val File by lazy { icon("File", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z") }
    val Camera by lazy { icon("Camera", "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z") }
    val Folder by lazy { icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z") }
    val Play by lazy { icon("Play", "M8,5v14l11,-7z") }
    // "Skip next": plays the next video automatically when one ends.
    val AutoScroll by lazy { icon("AutoScroll", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val RepeatOne by lazy { icon("RepeatOne", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z") }
    val Shuffle by lazy { icon("Shuffle", "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z") }
    val InOrder by lazy { icon("InOrder", "M2,17h2v0.5H3v1h1v0.5H2v1h3v-4H2v1zM3,8h1V4H2v1h1v3zM2,11h1.8L2,13.1v0.9h3v-1H3.2L5,10.9V10H2v1zM7,5v2h14V5H7zM7,19h14v-2H7v2zM7,13h14v-2H7v2z") }
    val SkipNext by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val SkipPrevious by lazy { icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }
    val Pause by lazy { icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Volume by lazy { icon("Volume", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z") }
    val DropDown by lazy { icon("DropDown", "M7,10l5,5 5,-5z") }
    val Back by lazy { icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black), // Icon() re-tints this
        ).build()
}
