package com.officesuite.app.word

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.MotionEvent
import android.widget.EditText
import kotlin.math.roundToInt

/** Formatting state at the cursor / selection, used to light up toolbar buttons. */
data class FormatState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Int = 12,
    val heading: Int? = null,
    val align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    val list: Boolean = false,
)

/** EditText with rich-text commands (spans), snapshot undo/redo and taps on embedded tables. */
@SuppressLint("AppCompatCustomView")
class RichEditText(ctx: Context) : EditText(ctx) {
    var onSelectionChange: ((Int, Int) -> Unit)? = null
    var onTableTap: ((TableSpan) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var onHistoryChanged: (() -> Unit)? = null

    private class Snap(val text: SpannableStringBuilder, val s: Int, val e: Int)
    private var undoStack: ArrayDeque<Snap>? = null
    private var redoStack: ArrayDeque<Snap>? = null
    private var restoring = false
    private var lastSnap = 0L
    private var ready = false
    val density = resources.displayMetrics.density

    val canUndo get() = undoStack?.isNotEmpty() == true
    val canRedo get() = redoStack?.isNotEmpty() == true

    init {
        undoStack = ArrayDeque()
        redoStack = ArrayDeque()
        setBackgroundColor(Color.TRANSPARENT)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = false
        setHorizontallyScrolling(false)
        textSize = 16f
        setTextColor(0xFF1B1D22.toInt())
        setLineSpacing(0f, 1.15f)
        val pad = (20 * density).toInt()
        setPadding(pad, pad, pad, pad * 4)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (ready && !restoring) snapshot(force = false)
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ready && !restoring) onChanged?.invoke()
            }
        })
    }

    /** Loads document content (not undoable). */
    fun load(content: CharSequence) {
        ready = false
        setText(content)
        undoStack?.clear(); redoStack?.clear()
        ready = true
        onHistoryChanged?.invoke()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke(selStart, selEnd)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) refreshObjectWidths()
    }

    fun contentWidth(): Int = (width - paddingLeft - paddingRight - (4 * density).toInt()).coerceAtLeast((200 * density).toInt())

    /** Tables/blocks are drawn at the editor width; re-set them so the layout picks up a new width. */
    fun refreshObjectWidths() {
        val t = text ?: return
        val w = contentWidth()
        for (sp in t.getSpans(0, t.length, TableSpan::class.java)) {
            if (sp.widthPx == w) continue
            sp.widthPx = w
            reset(t, sp)
        }
        for (sp in t.getSpans(0, t.length, OpaqueBlockSpan::class.java)) {
            if (sp.widthPx == w) continue
            sp.widthPx = w
            reset(t, sp)
        }
    }

    private fun reset(t: Editable, sp: Any) {
        val s = t.getSpanStart(sp); val e = t.getSpanEnd(sp); val f = t.getSpanFlags(sp)
        if (s < 0) return
        t.removeSpan(sp)
        t.setSpan(sp, s, e, f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - event.downTime < 300) {
            val off = getOffsetForPosition(event.x, event.y)
            val t = text
            if (t != null && off >= 0) {
                val lo = maxOf(0, off - 1)
                val hi = minOf(t.length, off + 1)
                val span = t.getSpans(lo, hi, TableSpan::class.java).firstOrNull()
                if (span != null) post { onTableTap?.invoke(span) }
            }
        }
        return super.onTouchEvent(event)
    }

    /* ---------------- undo / redo ---------------- */

    fun snapshot(force: Boolean = true) {
        val stack = undoStack ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSnap < 900) return
        lastSnap = now
        stack.addLast(Snap(SpannableStringBuilder(text ?: ""), selectionStart, selectionEnd))
        if (stack.size > 100) stack.removeFirst()
        redoStack?.clear()
        onHistoryChanged?.invoke()
    }

    private fun restore(s: Snap) {
        restoring = true
        setText(s.text)
        val len = text?.length ?: 0
        setSelection(s.s.coerceIn(0, len), s.e.coerceIn(0, len))
        restoring = false
        refreshObjectWidths()
        onChanged?.invoke()
        onHistoryChanged?.invoke()
    }

    fun undo() {
        val s = undoStack?.removeLastOrNull() ?: return
        redoStack?.addLast(Snap(SpannableStringBuilder(text ?: ""), selectionStart, selectionEnd))
        lastSnap = 0
        restore(s)
    }

    fun redo() {
        val s = redoStack?.removeLastOrNull() ?: return
        undoStack?.addLast(Snap(SpannableStringBuilder(text ?: ""), selectionStart, selectionEnd))
        lastSnap = 0
        restore(s)
    }

    /* ---------------- helpers ---------------- */

    private fun sel(): Pair<Int, Int> {
        val a = selectionStart.coerceAtLeast(0)
        val b = selectionEnd.coerceAtLeast(0)
        return minOf(a, b) to maxOf(a, b)
    }

    /** Spans that apply at the cursor (what newly typed text gets) or over a whole selection segment. */
    private fun activeAt(t: Spanned, pos: Int): List<Any> =
        t.getSpans(maxOf(0, pos - 1), pos, CharacterStyle::class.java).filter {
            val s = t.getSpanStart(it); val e = t.getSpanEnd(it)
            (s < pos && e >= pos) || (s == pos && e == pos) || (pos == 0 && s == 0 && e > 0)
        }

    /** True if every non-newline character in [s, e) is covered by a span matching [pred]. */
    private fun allHave(s: Int, e: Int, pred: (Any) -> Boolean): Boolean {
        val t = text ?: return false
        if (s == e) return activeAt(t, s).any(pred)
        var i = s
        while (i < e) {
            val next = t.nextSpanTransition(i, e, CharacterStyle::class.java)
            val seg = t.subSequence(i, next)
            if (seg.any { it != '\n' }) {
                val has = t.getSpans(i, next, CharacterStyle::class.java).any {
                    pred(it) && t.getSpanStart(it) <= i && t.getSpanEnd(it) >= next
                }
                if (!has) return false
            }
            i = next
        }
        return true
    }

    /** Removes spans of [cls] matching [filter] from [s, e), keeping the parts outside the range. */
    private fun <T : Any> clearRange(cls: Class<T>, s: Int, e: Int, filter: (T) -> Boolean = { true }, copy: (T) -> T?) {
        val t = text ?: return
        for (sp in t.getSpans(maxOf(0, s - 1), minOf(t.length, e + 1), cls)) {
            if (!filter(sp)) continue
            val ss = t.getSpanStart(sp); val se = t.getSpanEnd(sp)
            if (s == e) { if (!(ss < s && se >= s) && !(ss == s && se == s)) continue } else if (se <= s || ss >= e) continue
            t.removeSpan(sp)
            if (ss < s) copy(sp)?.let { t.setSpan(it, ss, s, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (se > e) copy(sp)?.let { t.setSpan(it, e, se, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
        }
    }

    private fun add(span: Any, s: Int, e: Int) {
        val t = text ?: return
        t.setSpan(span, s, e, if (s == e) Spanned.SPAN_INCLUSIVE_INCLUSIVE else Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
    }

    private fun done() {
        onChanged?.invoke()
        val (a, b) = sel()
        onSelectionChange?.invoke(a, b)
        invalidate()
    }

    /* ---------------- character formatting ---------------- */

    fun toggleStyle(bit: Int) {
        snapshot()
        val (s, e) = sel()
        val on = allHave(s, e) { it is StyleSpan && (it.style and bit) != 0 }
        if (on) {
            val t = text ?: return
            // keep the other bit of BOLD_ITALIC spans
            for (sp in t.getSpans(s, e, StyleSpan::class.java)) {
                if (sp.style == Typeface.BOLD_ITALIC) {
                    val ss = maxOf(s, t.getSpanStart(sp)); val se = minOf(e, t.getSpanEnd(sp))
                    if (se > ss) t.setSpan(StyleSpan(Typeface.BOLD_ITALIC and bit.inv()), ss, se, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
                }
            }
            clearRange(StyleSpan::class.java, s, e, { (it.style and bit) != 0 }) { StyleSpan(it.style) }
        } else add(StyleSpan(bit), s, e)
        done()
    }

    fun toggleUnderline() {
        snapshot()
        val (s, e) = sel()
        if (allHave(s, e) { it is UnderlineSpan }) clearRange(UnderlineSpan::class.java, s, e) { UnderlineSpan() }
        else add(UnderlineSpan(), s, e)
        done()
    }

    fun toggleStrike() {
        snapshot()
        val (s, e) = sel()
        if (allHave(s, e) { it is StrikethroughSpan }) clearRange(StrikethroughSpan::class.java, s, e) { StrikethroughSpan() }
        else add(StrikethroughSpan(), s, e)
        done()
    }

    fun setSizePt(pt: Int) {
        snapshot()
        val (s, e) = sel()
        clearRange(AbsoluteSizeSpan::class.java, s, e) { AbsoluteSizeSpan(it.size, it.dip) }
        add(AbsoluteSizeSpan((pt * 4f / 3f).roundToInt(), true), s, e)
        done()
    }

    fun setColor(color: Int?) {
        snapshot()
        val (s, e) = sel()
        clearRange(ForegroundColorSpan::class.java, s, e) { ForegroundColorSpan(it.foregroundColor) }
        if (color != null) add(ForegroundColorSpan(color), s, e)
        done()
    }

    fun setHighlight(color: Int?) {
        snapshot()
        val (s, e) = sel()
        clearRange(BackgroundColorSpan::class.java, s, e) { BackgroundColorSpan(it.backgroundColor) }
        if (color != null) add(BackgroundColorSpan(color), s, e)
        done()
    }

    fun clearFormatting() {
        snapshot()
        val (s, e) = sel()
        clearRange(StyleSpan::class.java, s, e) { StyleSpan(it.style) }
        clearRange(UnderlineSpan::class.java, s, e) { UnderlineSpan() }
        clearRange(StrikethroughSpan::class.java, s, e) { StrikethroughSpan() }
        clearRange(AbsoluteSizeSpan::class.java, s, e) { AbsoluteSizeSpan(it.size, it.dip) }
        clearRange(ForegroundColorSpan::class.java, s, e) { ForegroundColorSpan(it.foregroundColor) }
        clearRange(BackgroundColorSpan::class.java, s, e) { BackgroundColorSpan(it.backgroundColor) }
        done()
    }

    /* ---------------- paragraph formatting ---------------- */

    /** Paragraph boundaries [start, end) covering the selection; end includes the newline. */
    private fun paragraphs(): List<Pair<Int, Int>> {
        val t = text ?: return emptyList()
        val (s, e) = sel()
        val out = ArrayList<Pair<Int, Int>>()
        var ps = TextUtils.lastIndexOf(t, '\n', maxOf(0, s - 1)).let { if (s == 0) 0 else it + 1 }
        if (s > 0 && t[s - 1] == '\n') ps = s
        while (true) {
            val nl = TextUtils.indexOf(t, '\n', ps)
            val pe = if (nl < 0) t.length else nl + 1
            out.add(ps to pe)
            if (nl < 0 || pe > e || (pe == e && e > s)) break
            ps = pe
        }
        return out
    }

    private fun <T : Any> replaceParagraphSpan(cls: Class<T>, ps: Int, pe: Int, newSpan: Any?) {
        val t = text ?: return
        for (sp in t.getSpans(ps, maxOf(ps, pe - 1), cls)) {
            val ss = t.getSpanStart(sp); val se = t.getSpanEnd(sp)
            if (se <= ps || ss >= pe) continue
            t.removeSpan(sp)
            if (ss < ps) t.setSpan(sp, ss, ps, Spanned.SPAN_PARAGRAPH)
            if (se > pe) {
                val again = if (ss < ps) cloneParagraphSpan(sp) else sp
                if (again != null) t.setSpan(again, pe, se, Spanned.SPAN_PARAGRAPH)
            }
        }
        if (newSpan != null && pe > ps) t.setSpan(newSpan, ps, pe, Spanned.SPAN_PARAGRAPH)
    }

    private fun cloneParagraphSpan(sp: Any): Any? = when (sp) {
        is HeadingSpan -> HeadingSpan(sp.level)
        is ListSpan -> ListSpan(sp.numId, sp.ilvl, sp.density, sp.numbered)
        is AlignmentSpan.Standard -> AlignmentSpan.Standard(sp.alignment)
        is ParaPropsSpan -> ParaPropsSpan(sp.pPr)
        else -> null
    }

    fun setAlignment(a: Layout.Alignment) {
        snapshot()
        for ((ps, pe) in paragraphs()) replaceParagraphSpan(AlignmentSpan::class.java, ps, pe, AlignmentSpan.Standard(a))
        done()
    }

    fun setHeading(level: Int?) {
        snapshot()
        for ((ps, pe) in paragraphs()) replaceParagraphSpan(HeadingSpan::class.java, ps, pe, level?.let { HeadingSpan(it) })
        done()
    }

    fun toggleBullets() {
        snapshot()
        val t = text ?: return
        val paras = paragraphs()
        val allList = paras.all { (ps, pe) -> t.getSpans(ps, maxOf(ps, pe - 1), ListSpan::class.java).any { t.getSpanStart(it) <= ps && t.getSpanEnd(it) > ps } }
        for ((ps, pe) in paras) replaceParagraphSpan(ListSpan::class.java, ps, pe, if (allList) null else ListSpan(null, 0, density))
        done()
    }

    /* ---------------- insert objects ---------------- */

    /** Inserts an object character on its own paragraph and attaches [span]. */
    fun insertBlock(span: Any) {
        snapshot()
        val t = text ?: return
        val (s, _) = sel()
        val sb = StringBuilder()
        if (s > 0 && t[s - 1] != '\n') sb.append('\n')
        val objAt = s + sb.length
        sb.append(OBJ)
        sb.append('\n')
        t.insert(s, sb)
        t.setSpan(span, objAt, objAt + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSelection(minOf(t.length, objAt + 2))
        refreshObjectWidths()
        done()
    }

    fun insertInline(span: Any) {
        snapshot()
        val t = text ?: return
        val (s, e) = sel()
        t.replace(s, e, OBJ.toString())
        t.setSpan(span, s, s + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSelection(s + 1)
        done()
    }

    /** Re-attaches a table span after its contents changed, so it is measured again. */
    fun tableChanged(span: TableSpan) {
        val t = text ?: return
        reset(t, span)
        done()
    }

    fun deleteObject(span: Any) {
        snapshot()
        val t = text ?: return
        val s = t.getSpanStart(span)
        if (s < 0) return
        t.removeSpan(span)
        val end = if (s + 1 < t.length && t[s + 1] == '\n') s + 2 else s + 1
        t.delete(s, end)
        done()
    }

    /* ---------------- state for the toolbar ---------------- */

    fun formatState(): FormatState {
        val t = text ?: return FormatState()
        val (s, e) = sel()
        val at = if (s == e) activeAt(t, s) else t.getSpans(s, minOf(e, s + 1), CharacterStyle::class.java).toList()
        val size = at.filterIsInstance<AbsoluteSizeSpan>().lastOrNull()?.let { if (it.dip) (it.size * 0.75f).roundToInt() else 12 } ?: 12
        val ps = if (s == 0) 0 else TextUtils.lastIndexOf(t, '\n', s - 1) + 1
        val q = minOf(t.length, ps + 1)
        val heading = t.getSpans(ps, q, HeadingSpan::class.java).firstOrNull()?.level
        val align = t.getSpans(ps, q, AlignmentSpan::class.java).firstOrNull()?.alignment ?: Layout.Alignment.ALIGN_NORMAL
        val list = t.getSpans(ps, q, ListSpan::class.java).any()
        return FormatState(
            bold = allHave(s, e) { it is StyleSpan && (it.style and Typeface.BOLD) != 0 },
            italic = allHave(s, e) { it is StyleSpan && (it.style and Typeface.ITALIC) != 0 },
            underline = allHave(s, e) { it is UnderlineSpan },
            strike = allHave(s, e) { it is StrikethroughSpan },
            sizePt = size,
            heading = heading,
            align = align,
            list = list,
        )
    }

    /* ---------------- find & replace ---------------- */

    fun findNext(query: String, matchCase: Boolean): Boolean {
        val t = text ?: return false
        if (query.isEmpty()) return false
        val from = selectionEnd.coerceAtLeast(0)
        var i = t.toString().indexOf(query, from, !matchCase)
        if (i < 0) i = t.toString().indexOf(query, 0, !matchCase)
        if (i < 0) return false
        requestFocus()
        setSelection(i, i + query.length)
        return true
    }

    fun replaceCurrent(query: String, replacement: String, matchCase: Boolean): Boolean {
        val t = text ?: return false
        val (s, e) = sel()
        if (e > s && t.subSequence(s, e).toString().equals(query, !matchCase)) {
            snapshot()
            t.replace(s, e, replacement)
            setSelection(s + replacement.length)
            done()
        }
        return findNext(query, matchCase)
    }

    fun replaceAll(query: String, replacement: String, matchCase: Boolean): Int {
        val t = text ?: return 0
        if (query.isEmpty()) return 0
        snapshot()
        var count = 0
        var i = t.toString().indexOf(query, 0, !matchCase)
        while (i >= 0) {
            t.replace(i, i + query.length, replacement)
            count++
            i = t.toString().indexOf(query, i + replacement.length, !matchCase)
        }
        done()
        return count
    }

    fun wordCount(): Pair<Int, Int> {
        val s = (text ?: "").toString().replace(OBJ.toString(), " ")
        val words = s.split(Regex("\\s+")).count { it.isNotBlank() }
        return words to s.count { !it.isWhitespace() }
    }
}
