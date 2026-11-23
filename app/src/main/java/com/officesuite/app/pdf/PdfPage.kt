package com.officesuite.app.pdf

import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.SizeF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val INVERT = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

private object OverlayPaints {
    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
    val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF2A56C6.toInt() }
    val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A56C6.toInt() }
    val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
}

@Composable
fun PdfPageView(st: PdfEditorState, index: Int, pageSize: SizeF, renderWidth: Int, modifier: Modifier = Modifier) {
    val session = st.session ?: return
    val version = st.version
    val bmp by produceState<Bitmap?>(null, session, index, renderWidth, version) {
        value = session.render(index, renderWidth)
    }
    val density = LocalDensity.current.density
    val tool = st.tool
    if (tool == Tool.EDIT_TEXT) {
        LaunchedEffect(index, version) { st.requestLines(index) }
    }
    Box(
        modifier.fillMaxWidth()
            .aspectRatio((pageSize.width / pageSize.height).coerceIn(0.2f, 5f))
            .shadow(2.dp)
            .background(if (st.night) Color(0xFF1E1E1E) else Color.White)
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                colorFilter = if (st.night) INVERT else null
            )
        }
        Canvas(Modifier.fillMaxSize().pointerInput(tool, index) { pageGestures(st, index, density) }) {
            val pw = size.width
            val ph = size.height
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                if (st.tool == Tool.EDIT_TEXT) {
                    st.textLines[index]?.forEach { line ->
                        val p = OverlayPaints
                        val color = if (line.editable) 0xFF2A56C6.toInt() else 0xFFE8742A.toInt()
                        p.boxFill.color = (color and 0x00FFFFFF) or 0x14000000
                        p.box.color = (color and 0x00FFFFFF) or 0x99000000.toInt()
                        p.box.strokeWidth = 1f * density
                        val l = line.box.l * pw - 2 * density
                        val t = line.box.t * ph - 1 * density
                        val r = line.box.r * pw + 2 * density
                        val b = line.box.b * ph + 1 * density
                        nc.drawRect(l, t, r, b, p.boxFill)
                        nc.drawRect(l, t, r, b, p.box)
                    }
                }
                for (o in st.objects) if (o.page == index) ObjPainter.draw(nc, o, pw, ph, st.images)
                st.livePreview?.let { if (it.page == index) ObjPainter.draw(nc, it, pw, ph, st.images) }
                st.selected()?.takeIf { it.page == index }?.let { sel ->
                    val r = ObjPainter.bounds(sel, pw, ph)
                    r.inset(-4 * density, -4 * density)
                    val p = OverlayPaints
                    p.sel.strokeWidth = 1.5f * density
                    p.sel.pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
                    nc.drawRect(r, p.sel)
                    if (sel !is InkObj) {
                        nc.drawCircle(r.right, r.bottom, 9 * density, p.handleRing)
                        nc.drawCircle(r.right, r.bottom, 7 * density, p.handle)
                    }
                }
            }
        }
    }
}

private fun handleTap(st: PdfEditorState, page: Int, pos: Offset, pw: Float, ph: Float, density: Float) {
    val nx = pos.x / pw
    val ny = pos.y / ph
    when (st.tool) {
        Tool.VIEW -> {
            val hit = st.objects.lastOrNull { it.page == page && ObjPainter.hit(it, pos.x, pos.y, pw, ph, 8 * density) }
            when {
                hit == null -> st.selectedId = null
                hit.id == st.selectedId && hit is TextObj ->
                    st.textRequest = TextRequest(page, hit.x, hit.y, hit.text, hit.size, hit.color, editId = hit.id)
                else -> st.selectedId = hit.id
            }
        }
        Tool.ADD_TEXT -> st.textRequest = TextRequest(page, nx, ny, "", st.textSize, st.color)
        Tool.EDIT_TEXT -> {
            val obj = st.objects.lastOrNull { it is TextObj && it.page == page && ObjPainter.hit(it, pos.x, pos.y, pw, ph, 6 * density) } as TextObj?
            if (obj != null) {
                st.textRequest = TextRequest(page, obj.x, obj.y, obj.text, obj.size, obj.color, editId = obj.id)
                return
            }
            val lines = st.textLines[page]
            if (lines == null) {
                st.requestLines(page)
                st.message = "Reading the text on this page…"
                return
            }
            val tolX = 6 * density / pw
            val tolY = 4 * density / ph
            val line = lines.firstOrNull { it.box.inflate(tolX, tolY).contains(nx, ny) }
            if (line != null) st.textRequest = TextRequest(page, line.box.l, line.box.t, line.text, line.sizeNorm, line.color, line = line)
            else st.message = if (lines.isEmpty()) "No editable text on this page (it may be a scanned image)" else "Tap inside a highlighted line"
        }
        else -> {}
    }
}

private suspend fun PointerInputScope.pageGestures(st: PdfEditorState, page: Int, density: Float) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pw = size.width.toFloat()
        val ph = size.height.toFloat()
        if (pw <= 0f || ph <= 0f) return@awaitEachGesture
        when (st.tool) {
            Tool.PEN, Tool.HIGHLIGHT -> {
                down.consume()
                val hl = st.tool == Tool.HIGHLIGHT
                val pts = ArrayList<Float>()
                pts.add(down.position.x / pw); pts.add(down.position.y / ph)
                val id = st.newId()
                fun make() = InkObj(id, page, pts.toFloatArray(), st.color, if (hl) st.strokeWidth * 4f else st.strokeWidth, hl)
                st.livePreview = make()
                var cancelled = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } > 1) { cancelled = true; break }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val lx = pts[pts.size - 2] * pw
                    val ly = pts[pts.size - 1] * ph
                    if (hypot(ch.position.x - lx, ch.position.y - ly) > 1.5f) {
                        pts.add(ch.position.x / pw); pts.add(ch.position.y / ph)
                        st.livePreview = make()
                    }
                    ch.consume()
                }
                st.livePreview = null
                if (!cancelled) st.addObject(make())
            }
            Tool.ERASER -> {
                down.consume()
                var pushed = false
                fun eraseAt(p: Offset) {
                    val hit = st.objects.filter { it.page == page && ObjPainter.hit(it, p.x, p.y, pw, ph, 10 * density) }
                    if (hit.isNotEmpty()) {
                        if (!pushed) { st.pushObjectsUndo(); pushed = true }
                        st.objects.removeAll(hit)
                    }
                }
                eraseAt(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    eraseAt(ch.position)
                    ch.consume()
                }
            }
            Tool.WHITEOUT -> {
                down.consume()
                val start = down.position
                var end = start
                val id = st.newId()
                fun rect() = RectObj(
                    id, page, min(start.x, end.x) / pw, min(start.y, end.y) / ph,
                    abs(end.x - start.x) / pw, abs(end.y - start.y) / ph, 0xFFFFFFFF.toInt()
                )
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    end = ch.position
                    st.livePreview = rect()
                    ch.consume()
                }
                st.livePreview = null
                if (abs(end.x - start.x) > 6 * density && abs(end.y - start.y) > 4 * density) st.addObject(rect())
            }
            else -> {
                val sel = st.selected()?.takeIf { it.page == page }
                val selRect = sel?.let { ObjPainter.bounds(it, pw, ph).apply { inset(-4 * density, -4 * density) } }
                val onHandle = selRect != null && sel !is InkObj && hypot(down.position.x - selRect.right, down.position.y - selRect.bottom) < 24 * density
                val onBody = selRect != null && selRect.contains(down.position.x, down.position.y)
                if (st.tool == Tool.VIEW && sel != null && (onHandle || onBody)) {
                    down.consume()
                    var moved = false
                    var last = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (!moved && (ch.position - down.position).getDistance() > slop) { moved = true; st.pushObjectsUndo() }
                        if (moved) {
                            val d = ch.position - last
                            last = ch.position
                            if (onHandle) st.resizeSelected(d.x, d.y, pw, ph)
                            else st.updateObject(sel.id) { it.moved(d.x / pw, d.y / ph) }
                        }
                        ch.consume()
                    }
                    if (!moved) handleTap(st, page, down.position, pw, ph, density)
                } else {
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) moved = true
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if ((ch.position - down.position).getDistance() > slop) moved = true
                    }
                    if (!moved) handleTap(st, page, down.position, pw, ph, density)
                }
            }
        }
    }
}

/** Keeps [v] within [lo, hi] even if the range is inverted. */
internal fun clampRange(v: Float, lo: Float, hi: Float) = max(min(lo, hi), min(v, max(lo, hi)))
