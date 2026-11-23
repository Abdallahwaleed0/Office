package com.officesuite.app.sheet

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.pow

/** Column/row positions in pixels for the current sheet and zoom. */
class GridGeom(sh: Sheet, val density: Float, val zoom: Float) {
    val hdrW = 46f * density
    val hdrH = 26f * density
    private fun colPx(w: Float) = (w * 7f + 5f) * 1.2f * density * zoom
    private fun rowPx(h: Float) = h * 1.6f * density * zoom
    val defColW = colPx(8.43f)
    val defRowH = rowPx(15f)
    private val nCols = maxOf(sh.maxCol() + 1, 26) + 50
    private val nRows = maxOf(sh.maxRow() + 1, 100) + 300
    private val colStart = FloatArray(nCols + 1)
    private val rowStart = FloatArray(nRows + 1)

    init {
        for (c in 0 until nCols) colStart[c + 1] = colStart[c] + (sh.colWidths[c]?.let { colPx(it) } ?: defColW)
        for (r in 0 until nRows) rowStart[r + 1] = rowStart[r] + (sh.rowHeights[r]?.let { rowPx(it) } ?: defRowH)
    }

    fun colX(c: Int): Float = if (c <= nCols) colStart[c] else colStart[nCols] + (c - nCols) * defColW
    fun rowY(r: Int): Float = if (r <= nRows) rowStart[r] else rowStart[nRows] + (r - nRows).toFloat() * defRowH
    fun colW(c: Int) = colX(c + 1) - colX(c)
    fun rowH(r: Int) = rowY(r + 1) - rowY(r)

    private fun search(arr: FloatArray, n: Int, v: Float, def: Float): Int {
        if (v <= 0f) return 0
        if (v >= arr[n]) return n + ((v - arr[n]) / def).toInt()
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (arr[mid] <= v) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun colAt(x: Float) = search(colStart, nCols, x, defColW).coerceIn(0, Sheet.MAX_COLS - 1)
    fun rowAt(y: Float) = search(rowStart, nRows, y, defRowH).coerceIn(0, Sheet.MAX_ROWS - 1)
}

private object GridPaints {
    val fill = Paint()
    val line = Paint().apply { strokeWidth = 1f }
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val header = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
}

private fun drawGrid(cv: Canvas, w: Float, h: Float, st: SheetEditorState, g: GridGeom, accent: Int) {
    val wb = st.wb ?: return
    val sh = st.sheet ?: return
    val ev = st.ev
    val d = g.density
    val p = GridPaints
    val sx = st.scrollX
    val sy = st.scrollY
    val firstC = g.colAt(sx)
    val lastC = g.colAt(sx + w - g.hdrW)
    val firstR = g.rowAt(sy)
    val lastR = g.rowAt(sy + h - g.hdrH)

    cv.drawColor(0xFFFFFFFF.toInt())
    cv.save()
    cv.clipRect(g.hdrW, g.hdrH, w, h)
    cv.translate(g.hdrW - sx, g.hdrH - sy)

    // cell fills
    for (r in firstR..lastR) for (c in firstC..lastC) {
        val cell = sh[r, c] ?: continue
        val fc = wb.style(cell.style).fillColor ?: continue
        p.fill.color = fc
        cv.drawRect(g.colX(c), g.rowY(r), g.colX(c + 1), g.rowY(r + 1), p.fill)
    }
    // selection fill
    val sr1 = maxOf(st.r1, firstR); val sr2 = minOf(st.r2, lastR)
    val sc1 = maxOf(st.c1, firstC); val sc2 = minOf(st.c2, lastC)
    if (sr1 <= sr2 && sc1 <= sc2) {
        p.fill.color = (accent and 0x00FFFFFF) or 0x1F000000
        cv.drawRect(g.colX(sc1), g.rowY(sr1), g.colX(sc2 + 1), g.rowY(sr2 + 1), p.fill)
    }
    // grid lines
    p.line.color = 0xFFE1E3E8.toInt()
    val top = g.rowY(firstR); val bottom = g.rowY(lastR + 1)
    val left = g.colX(firstC); val right = g.colX(lastC + 1)
    for (c in firstC..lastC + 1) { val x = g.colX(c); cv.drawLine(x, top, x, bottom, p.line) }
    for (r in firstR..lastR + 1) { val y = g.rowY(r); cv.drawLine(left, y, right, y, p.line) }

    // text
    if (ev != null) {
        val base = 13f * d * g.zoom
        for (r in firstR..lastR) {
            var c = firstC
            while (c <= lastC) {
                val cell = sh[r, c]
                if (cell == null || cell.raw.isEmpty()) { c++; continue }
                val style = wb.style(cell.style)
                val v = SheetText.value(ev, sh, r, c)
                val text = SheetText.display(wb, ev, sh, r, c)
                if (text.isEmpty()) { c++; continue }
                val tp = p.text
                tp.textSize = base * (style.fontSize / 11f)
                tp.typeface = Typeface.create(
                    Typeface.DEFAULT,
                    (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
                )
                tp.isUnderlineText = style.underline
                tp.isStrikeThruText = style.strike
                tp.color = when {
                    v is V.Err -> 0xFFC62828.toInt()
                    else -> style.fontColor ?: 0xFF1B1D22.toInt()
                }
                val align = when (style.hAlign) {
                    HAlign.GENERAL -> when (v) { is V.Num -> HAlign.RIGHT; is V.Bool, is V.Err -> HAlign.CENTER; else -> HAlign.LEFT }
                    else -> style.hAlign
                }
                val x0 = g.colX(c); val y0 = g.rowY(r)
                val cw = g.colW(c); val ch = g.rowH(r)
                val pad = 4f * d * g.zoom
                cv.save()
                if (style.wrap) {
                    cv.clipRect(x0, y0, x0 + cw, y0 + ch)
                    val lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, (cw - 2 * pad).toInt().coerceAtLeast(1))
                        .setAlignment(
                            when (align) { HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER; HAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
                        ).build()
                    cv.translate(x0 + pad, y0 + pad / 2)
                    lay.draw(cv)
                } else {
                    // left-aligned text may overflow into empty cells to the right
                    var clipRight = x0 + cw
                    if (align == HAlign.LEFT && v is V.Str) {
                        val need = tp.measureText(text) + 2 * pad
                        var nc = c + 1
                        while (clipRight - x0 < need && nc <= lastC + 5 && (sh[r, nc]?.raw ?: "").isEmpty()) {
                            clipRight = g.colX(nc + 1); nc++
                        }
                    }
                    cv.clipRect(x0, y0, clipRight, y0 + ch)
                    val fm = tp.fontMetrics
                    val baseline = y0 + ch / 2f - (fm.ascent + fm.descent) / 2f
                    val tw = tp.measureText(text)
                    val tx = when (align) {
                        HAlign.RIGHT -> x0 + cw - pad - tw
                        HAlign.CENTER -> x0 + (cw - tw) / 2f
                        else -> x0 + pad
                    }
                    cv.drawText(text, tx, baseline, tp)
                }
                cv.restore()
                c++
            }
        }
    }

    // selection border + fill handle
    p.sel.color = accent
    p.sel.strokeWidth = 2f * d
    val bx1 = g.colX(st.c1); val by1 = g.rowY(st.r1)
    val bx2 = if (st.c2 >= Sheet.MAX_COLS - 1) right + 2 * d else g.colX(st.c2 + 1)
    val by2 = if (st.r2 >= Sheet.MAX_ROWS - 1) bottom + 2 * d else g.rowY(st.r2 + 1)
    cv.drawRect(bx1, by1, bx2, by2, p.sel)
    if (st.r1 != st.r2 || st.c1 != st.c2) {
        p.sel.strokeWidth = 1f * d
        cv.drawRect(g.colX(st.selC) + d, g.rowY(st.selR) + d, g.colX(st.selC + 1) - d, g.rowY(st.selR + 1) - d, p.sel)
    }
    p.fill.color = accent
    cv.drawRect(bx2 - 3 * d, by2 - 3 * d, bx2 + 3 * d, by2 + 3 * d, p.fill)
    cv.restore()

    // headers
    p.fill.color = 0xFFF3F4F6.toInt()
    cv.drawRect(0f, 0f, w, g.hdrH, p.fill)
    cv.drawRect(0f, 0f, g.hdrW, h, p.fill)
    p.header.textSize = 11.5f * d
    val hfm = p.header.fontMetrics
    p.line.color = 0xFFD5D8DE.toInt()
    cv.save()
    cv.clipRect(g.hdrW, 0f, w, g.hdrH)
    for (c in firstC..lastC) {
        val x = g.colX(c) - sx + g.hdrW
        val selected = c in st.c1..st.c2
        if (selected) { p.fill.color = (accent and 0x00FFFFFF) or 0x33000000; cv.drawRect(x, 0f, x + g.colW(c), g.hdrH, p.fill) }
        p.header.color = if (selected) accent else 0xFF5D6371.toInt()
        cv.drawText(Addr.colName(c), x + g.colW(c) / 2f, g.hdrH / 2f - (hfm.ascent + hfm.descent) / 2f, p.header)
        cv.drawLine(x, 0f, x, g.hdrH, p.line)
    }
    cv.restore()
    cv.save()
    cv.clipRect(0f, g.hdrH, g.hdrW, h)
    for (r in firstR..lastR) {
        val y = g.rowY(r) - sy + g.hdrH
        val selected = r in st.r1..st.r2
        if (selected) { p.fill.color = (accent and 0x00FFFFFF) or 0x33000000; cv.drawRect(0f, y, g.hdrW, y + g.rowH(r), p.fill) }
        p.header.color = if (selected) accent else 0xFF5D6371.toInt()
        cv.drawText((r + 1).toString(), g.hdrW / 2f, y + g.rowH(r) / 2f - (hfm.ascent + hfm.descent) / 2f, p.header)
        cv.drawLine(0f, y, g.hdrW, y, p.line)
    }
    cv.restore()
    cv.drawLine(g.hdrW, 0f, g.hdrW, h, p.line)
    cv.drawLine(0f, g.hdrH, w, g.hdrH, p.line)
}

@Composable
fun SheetGrid(st: SheetEditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val scope = rememberCoroutineScope()
    val sh = st.sheet ?: return
    val version = st.version
    val zoom = st.zoom
    val geom = remember(sh, version, zoom, density) { GridGeom(sh, density, zoom) }
    val holder = remember { arrayOfNulls<Job>(1) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // scroll to a "Go to" target, or keep the active cell visible after keyboard navigation
    LaunchedEffect(st.selR, st.selC, st.scrollTarget, viewSize) {
        val t = st.scrollTarget
        if (t != null) {
            st.scrollX = (geom.colX(t.second) - 40f * density).coerceAtLeast(0f)
            st.scrollY = (geom.rowY(t.first) - 40f * density).coerceAtLeast(0f)
            st.scrollTarget = null
            return@LaunchedEffect
        }
        if (viewSize.width == 0 || st.r1 != st.r2 || st.c1 != st.c2) return@LaunchedEffect
        val visW = viewSize.width - geom.hdrW
        val visH = viewSize.height - geom.hdrH
        val x0 = geom.colX(st.selC); val x1 = geom.colX(st.selC + 1)
        val y0 = geom.rowY(st.selR); val y1 = geom.rowY(st.selR + 1)
        if (x0 < st.scrollX) st.scrollX = x0 else if (x1 > st.scrollX + visW) st.scrollX = (x1 - visW).coerceAtLeast(0f)
        if (y0 < st.scrollY) st.scrollY = y0 else if (y1 > st.scrollY + visH) st.scrollY = (y1 - visH).coerceAtLeast(0f)
    }
    val lastTap = remember { longArrayOf(0L) }

    fun cellAt(pos: Offset): Pair<Int, Int> =
        geom.rowAt(pos.y - geom.hdrH + st.scrollY) to geom.colAt(pos.x - geom.hdrW + st.scrollX)

    fun onTap(pos: Offset, double: Boolean) {
        val g = geom
        if (pos.x < g.hdrW && pos.y < g.hdrH) {
            st.commitEditIfAny()
            st.select(0, 0, maxOf(sh.maxRow(), 0), maxOf(sh.maxCol(), 0)); return
        }
        if (pos.y < g.hdrH) {
            val c = g.colAt(pos.x - g.hdrW + st.scrollX)
            st.commitEditIfAny(); st.select(0, c, Sheet.MAX_ROWS - 1, c); return
        }
        if (pos.x < g.hdrW) {
            val r = g.rowAt(pos.y - g.hdrH + st.scrollY)
            st.commitEditIfAny(); st.select(r, 0, r, Sheet.MAX_COLS - 1); return
        }
        val (r, c) = cellAt(pos)
        if (st.wantsReference()) { st.insertReference(r, c); return }
        if (double && r == st.selR && c == st.selC) { st.startEditing(); return }
        st.commitEditIfAny()
        st.select(r, c)
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(sh, geom) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    holder[0]?.cancel()
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    // decide: tap / long-press selection / pan-zoom
                    val decision = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size > 1) return@withTimeoutOrNull "pan"
                            val ch = ev.changes.first()
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            if (!ch.pressed) return@withTimeoutOrNull "tap"
                            if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull "pan"
                        }
                        "tap"
                    }
                    when (decision) {
                        "tap" -> {
                            val now = down.uptimeMillis
                            val dbl = now - lastTap[0] < 320
                            lastTap[0] = now
                            onTap(down.position, dbl)
                        }
                        null -> {
                            // long press: range selection by dragging
                            if (down.position.x > geom.hdrW && down.position.y > geom.hdrH) {
                                val (r0, c0) = cellAt(down.position)
                                if (st.wantsReference()) {
                                    var last = r0 to c0
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        last = cellAt(ch.position); ch.consume()
                                    }
                                    st.insertReference(r0, c0, last.first, last.second)
                                } else {
                                    st.commitEditIfAny()
                                    st.select(r0, c0)
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        val (r, c) = cellAt(ch.position)
                                        st.extendTo(r, c)
                                        ch.consume()
                                    }
                                }
                            }
                        }
                        else -> {
                            // pan / pinch zoom
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed }) break
                                val zoomChange = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                if (zoomChange != 1f) {
                                    val centroid = ev.calculateCentroid()
                                    val nz = (st.zoom * zoomChange).coerceIn(0.4f, 3f)
                                    val f = nz / st.zoom
                                    st.scrollX = ((st.scrollX + centroid.x - geom.hdrW) * f - (centroid.x - geom.hdrW)).coerceAtLeast(0f)
                                    st.scrollY = ((st.scrollY + centroid.y - geom.hdrH) * f - (centroid.y - geom.hdrH)).coerceAtLeast(0f)
                                    st.zoom = nz
                                }
                                st.scrollX = (st.scrollX - pan.x).coerceAtLeast(0f)
                                st.scrollY = (st.scrollY - pan.y).coerceAtLeast(0f)
                                ev.changes.forEach {
                                    if (it.id == down.id) vt.addPosition(it.uptimeMillis, it.position)
                                    it.consume()
                                }
                            }
                            val v = vt.calculateVelocity()
                            if (abs(v.x) + abs(v.y) > 300f) {
                                holder[0] = scope.launch {
                                    var vx = v.x
                                    var vy = v.y
                                    var last = withFrameNanos { it }
                                    while (isActive && abs(vx) + abs(vy) > 40f) {
                                        val now = withFrameNanos { it }
                                        val dt = (now - last) / 1e9f
                                        last = now
                                        st.scrollX = (st.scrollX - vx * dt).coerceAtLeast(0f)
                                        st.scrollY = (st.scrollY - vy * dt).coerceAtLeast(0f)
                                        val decay = 0.05f.pow(dt)
                                        vx *= decay; vy *= decay
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        drawIntoCanvas { c ->
            drawGrid(c.nativeCanvas, size.width, size.height, st, geom, accent)
        }
    }
}
