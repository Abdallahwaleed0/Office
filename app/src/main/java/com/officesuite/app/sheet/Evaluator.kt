package com.officesuite.app.sheet

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

sealed class V {
    object Empty : V()
    data class Num(val v: Double) : V()
    data class Str(val v: String) : V()
    data class Bool(val v: Boolean) : V()
    data class Err(val code: String) : V()
    class Area(val sheet: Sheet, val r1: Int, val c1: Int, val r2: Int, val c2: Int) : V()
}

/**
 * Evaluates formulas against a snapshot of the workbook (so it can run on a background thread).
 * Call [snapshot] on the thread that owns the workbook, then [computeAll] anywhere.
 */
class Evaluator(private val wb: Workbook) {
    private val snap = HashMap<Sheet, Map<Long, Cell>>()
    private val bounds = HashMap<Sheet, IntArray>()
    private val results = HashMap<Sheet, HashMap<Long, V>>()
    private val visiting = HashSet<Pair<Sheet, Long>>()
    private val astCache = HashMap<String, Nd>()
    var curRow = 0
        private set
    var curCol = 0
        private set

    fun snapshot(): Evaluator {
        snap.clear(); bounds.clear(); results.clear()
        for (s in wb.sheets) {
            snap[s] = HashMap(s.cells)
            bounds[s] = intArrayOf(s.maxRow(), s.maxCol())
        }
        return this
    }

    fun computeAll(): Evaluator {
        for (s in wb.sheets) {
            val cells = snap[s] ?: continue
            for ((k, cell) in cells) if (cell.isFormula) value(s, Sheet.rowOf(k), Sheet.colOf(k))
        }
        return this
    }

    /** Value already computed by [computeAll] (UI thread friendly). */
    fun cached(s: Sheet, r: Int, c: Int): V? = results[s]?.get(Sheet.key(r, c))

    fun value(s: Sheet, r: Int, c: Int): V {
        val cells = snap[s] ?: return V.Empty
        val k = Sheet.key(r, c)
        val cell = cells[k] ?: return V.Empty
        if (!cell.isFormula) return literal(cell)
        results[s]?.get(k)?.let { return it }
        val vk = s to k
        if (!visiting.add(vk)) return V.Err("#CIRC!")
        val pr = curRow
        val pc = curCol
        curRow = r; curCol = c
        val v: V = try {
            val nd = astCache.getOrPut(cell.raw) { Parser.parse(cell.raw.substring(1)) }
            val x = scalar(eval(nd, s))
            if (x is V.Num && (x.v.isNaN() || x.v.isInfinite())) V.Err("#NUM!") else x
        } catch (e: FormulaError) {
            V.Err(e.code)
        } catch (e: StackOverflowError) {
            V.Err("#DEPTH!")
        } catch (e: Exception) {
            V.Err("#VALUE!")
        }
        curRow = pr; curCol = pc
        visiting.remove(vk)
        results.getOrPut(s) { HashMap() }[k] = v
        return v
    }

    private fun sheetOf(name: String?, cur: Sheet): Sheet? = if (name == null) cur else wb.sheet(name)

    fun eval(n: Nd, s: Sheet): V {
        when (n) {
            is NLit -> return n.v
            is NMissing -> return V.Empty
            is NRef -> {
                val sh = sheetOf(n.sheet, s) ?: return V.Err("#REF!")
                return value(sh, n.r, n.c)
            }
            is NArea -> {
                val sh = sheetOf(n.sheet, s) ?: return V.Err("#REF!")
                return V.Area(sh, n.r1, n.c1, n.r2, n.c2)
            }
            is NUn -> {
                val x = scalar(eval(n.x, s))
                return if (n.op == "-") V.Num(-num(x)) else V.Num(num(x) / 100.0)
            }
            is NBin -> return binary(n.op, scalar(eval(n.a, s)), scalar(eval(n.b, s)))
            is NCall -> return Functions.call(this, n.name, n.args, s)
        }
    }

    fun scalar(v: V): V = if (v is V.Area) value(v.sheet, v.r1, v.c1) else v

    /** Resolves a reference argument to an area (a single cell becomes a 1x1 area). */
    fun areaOf(n: Nd, s: Sheet): V.Area {
        if (n is NRef) {
            val sh = sheetOf(n.sheet, s) ?: throw FormulaError("#REF!")
            return V.Area(sh, n.r, n.c, n.r, n.c)
        }
        val v = eval(n, s)
        if (v is V.Area) return v
        if (v is V.Err) throw FormulaError(v.code)
        throw FormulaError("#VALUE!")
    }

    /** Evaluates and returns a scalar, converting errors into values (for IFERROR etc.). */
    fun safe(n: Nd, s: Sheet): V = try {
        scalar(eval(n, s))
    } catch (e: FormulaError) {
        V.Err(e.code)
    }

    private fun binary(op: String, a: V, b: V): V {
        when (op) {
            "+" -> return V.Num(num(a) + num(b))
            "-" -> return V.Num(num(a) - num(b))
            "*" -> return V.Num(num(a) * num(b))
            "/" -> {
                val x = num(a)
                val d = num(b)
                if (d == 0.0) throw FormulaError("#DIV/0!")
                return V.Num(x / d)
            }
            "^" -> return V.Num(num(a).pow(num(b)))
            "&" -> return V.Str(str(a) + str(b))
        }
        if (a is V.Err) throw FormulaError(a.code)
        if (b is V.Err) throw FormulaError(b.code)
        val c = compare(a, b)
        return V.Bool(
            when (op) {
                "=" -> c == 0
                "<>" -> c != 0
                "<" -> c < 0
                ">" -> c > 0
                "<=" -> c <= 0
                else -> c >= 0
            }
        )
    }

    /* ---------- coercions ---------- */

    fun num(v: V): Double = when (v) {
        is V.Num -> v.v
        is V.Bool -> if (v.v) 1.0 else 0.0
        is V.Empty -> 0.0
        is V.Str -> parseNumber(v.v) ?: throw FormulaError("#VALUE!")
        is V.Err -> throw FormulaError(v.code)
        is V.Area -> num(scalar(v))
    }

    fun str(v: V): String = when (v) {
        is V.Num -> NumFmt.general(v.v)
        is V.Str -> v.v
        is V.Bool -> if (v.v) "TRUE" else "FALSE"
        is V.Empty -> ""
        is V.Err -> throw FormulaError(v.code)
        is V.Area -> str(scalar(v))
    }

    fun bool(v: V): Boolean = when (v) {
        is V.Bool -> v.v
        is V.Num -> v.v != 0.0
        is V.Empty -> false
        is V.Str -> when {
            v.v.equals("TRUE", true) -> true
            v.v.equals("FALSE", true) -> false
            else -> throw FormulaError("#VALUE!")
        }
        is V.Err -> throw FormulaError(v.code)
        is V.Area -> bool(scalar(v))
    }

    private fun rank(v: V) = when (v) { is V.Num -> 0; is V.Str -> 1; is V.Bool -> 2; else -> 3 }

    fun compare(a0: V, b0: V): Int {
        val a = if (a0 is V.Empty) when (b0) { is V.Str -> V.Str(""); is V.Bool -> V.Bool(false); else -> V.Num(0.0) } else a0
        val b = if (b0 is V.Empty) when (a) { is V.Str -> V.Str(""); is V.Bool -> V.Bool(false); else -> V.Num(0.0) } else b0
        val ra = rank(a)
        val rb = rank(b)
        if (ra != rb) return ra.compareTo(rb)
        return when (a) {
            is V.Num -> a.v.compareTo((b as V.Num).v)
            is V.Str -> a.v.compareTo((b as V.Str).v, ignoreCase = true)
            is V.Bool -> a.v.compareTo((b as V.Bool).v)
            else -> 0
        }
    }

    fun sameKind(a: V, b: V) = rank(a) == rank(b)

    /* ---------- areas ---------- */

    fun clampedArea(a: V.Area): IntArray {
        val bd = bounds[a.sheet] ?: intArrayOf(-1, -1)
        return intArrayOf(a.r1, a.c1, minOf(a.r2, maxOf(bd[0], a.r1)), minOf(a.c2, maxOf(bd[1], a.c1)))
    }

    /** Row-major cell values of an area (clamped to the used range). */
    fun cells(a: V.Area): List<V> {
        val b = clampedArea(a)
        val r1 = b[0]; val c1 = b[1]; val r2 = b[2]; val c2 = b[3]
        val out = ArrayList<V>()
        for (r in r1..r2) for (c in c1..c2) out.add(value(a.sheet, r, c))
        return out
    }

    fun at(a: V.Area, dr: Int, dc: Int): V = value(a.sheet, a.r1 + dr, a.c1 + dc)

    fun rows(a: V.Area) = clampedArea(a)[2] - a.r1 + 1
    fun cols(a: V.Area) = clampedArea(a)[3] - a.c1 + 1

    /** Numbers for SUM-like functions: references ignore text/booleans, direct values are coerced. */
    fun numbers(args: List<Nd>, s: Sheet): List<Double> {
        val out = ArrayList<Double>()
        for (a in args) {
            if (a is NMissing) continue
            val v = eval(a, s)
            val isRef = a is NRef || a is NArea || v is V.Area
            if (v is V.Area) {
                for (cv in cells(v)) when (cv) {
                    is V.Num -> out.add(cv.v)
                    is V.Err -> throw FormulaError(cv.code)
                    else -> {}
                }
            } else if (isRef) {
                when (v) {
                    is V.Num -> out.add(v.v)
                    is V.Err -> throw FormulaError(v.code)
                    else -> {}
                }
            } else {
                out.add(num(v))
            }
        }
        return out
    }

    /** All values (flattened) of the given arguments. */
    fun flatten(args: List<Nd>, s: Sheet): List<V> {
        val out = ArrayList<V>()
        for (a in args) {
            val v = eval(a, s)
            if (v is V.Area) out.addAll(cells(v)) else out.add(v)
        }
        return out
    }

    companion object {
        private val NUM_RE = Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$")
        private val THOUSANDS_RE = Regex("^[-+]?\\d{1,3}(,\\d{3})+(\\.\\d+)?$")

        fun parseNumber(s0: String): Double? {
            var s = s0.trim()
            if (s.isEmpty()) return null
            var div = 1.0
            if (s.endsWith("%")) { s = s.dropLast(1).trim(); div = 100.0 }
            if (THOUSANDS_RE.matches(s)) s = s.replace(",", "")
            if (!NUM_RE.matches(s)) return null
            return s.toDoubleOrNull()?.div(div)
        }

        fun literal(cell: Cell): V {
            val raw = cell.raw
            if (raw.isEmpty()) return V.Empty
            if (cell.text) return V.Str(raw)
            if (raw.startsWith("'")) return V.Str(raw.substring(1))
            parseNumber(raw)?.let { return V.Num(it) }
            if (raw.equals("TRUE", true)) return V.Bool(true)
            if (raw.equals("FALSE", true)) return V.Bool(false)
            if (raw.startsWith("#") && raw.uppercase() in STANDARD_ERRORS) return V.Err(raw.uppercase())
            return V.Str(raw)
        }
    }
}

/* ===================== Dates ===================== */

object XlDate {
    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    fun toSerial(d: LocalDate): Double = ChronoUnit.DAYS.between(EPOCH, d).toDouble()
    fun toSerial(t: LocalDateTime): Double = toSerial(t.toLocalDate()) + t.toLocalTime().toSecondOfDay() / 86400.0
    fun fromSerial(v: Double): LocalDateTime {
        val days = floor(v).toLong()
        val secs = Math.round((v - days) * 86400.0).coerceIn(0, 86399)
        return EPOCH.plusDays(days).atStartOfDay().plusSeconds(secs)
    }
}

/* ===================== Functions ===================== */

object Functions {
    val NAMES = listOf(
        "SUM", "AVERAGE", "COUNT", "COUNTA", "MIN", "MAX", "IF", "IFERROR", "ROUND", "SUMIF", "COUNTIF",
        "AVERAGEIF", "SUMIFS", "COUNTIFS", "VLOOKUP", "HLOOKUP", "XLOOKUP", "INDEX", "MATCH", "AND", "OR", "NOT",
        "CONCATENATE", "CONCAT", "TEXTJOIN", "LEFT", "RIGHT", "MID", "LEN", "UPPER", "LOWER", "PROPER", "TRIM",
        "SUBSTITUTE", "REPLACE", "FIND", "SEARCH", "TEXT", "VALUE", "TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY",
        "WEEKDAY", "EDATE", "EOMONTH", "DAYS", "HOUR", "MINUTE", "SECOND", "TIME", "ABS", "SQRT", "POWER", "MOD",
        "INT", "TRUNC", "ROUNDUP", "ROUNDDOWN", "CEILING", "FLOOR", "PRODUCT", "SUMPRODUCT", "MEDIAN", "STDEV",
        "STDEV.P", "VAR", "VAR.P", "LARGE", "SMALL", "RANK", "PI", "RAND", "RANDBETWEEN", "EXP", "LN", "LOG",
        "LOG10", "SIGN", "COUNTBLANK", "ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "ISNA", "NA", "IFNA", "CHOOSE",
        "ROWS", "COLUMNS", "ROW", "COLUMN", "REPT", "EXACT", "CHAR", "CODE", "TRUE", "FALSE", "XOR", "IFS", "SWITCH",
        "MAXIFS", "MINIFS", "AVERAGEIFS", "PMT", "FV", "PV", "NPV"
    ).sorted()

    private fun need(args: List<Nd>, min: Int, max: Int = min) {
        if (args.size < min || args.size > max) throw FormulaError("#VALUE!")
    }

    private fun round(x: Double, digits: Int, mode: RoundingMode): Double =
        BigDecimal(x.toString()).setScale(digits, mode).toDouble()

    private fun wildcard(p: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '~' && i + 1 < p.length -> { sb.append(Regex.escape(p[i + 1].toString())); i++ }
                ch == '*' -> sb.append(".*")
                ch == '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
            i++
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    fun criteria(ev: Evaluator, crit: V): (V) -> Boolean {
        when (crit) {
            is V.Num -> return { v -> (v is V.Num && v.v == crit.v) || (v is V.Str && Evaluator.parseNumber(v.v) == crit.v) }
            is V.Bool -> return { v -> v is V.Bool && v.v == crit.v }
            is V.Err -> throw FormulaError(crit.code)
            else -> {}
        }
        val s = ev.str(crit)
        val op = listOf(">=", "<=", "<>", ">", "<", "=").firstOrNull { s.startsWith(it) } ?: ""
        val rhs = s.substring(op.length)
        val rn = Evaluator.parseNumber(rhs)
        val rx = if (rhs.isNotEmpty() && (op == "" || op == "=" || op == "<>")) wildcard(rhs) else null
        return { v ->
            when (op) {
                ">", "<", ">=", "<=" -> {
                    val c: Int? = if (rn != null) {
                        if (v is V.Num) v.v.compareTo(rn) else null
                    } else {
                        if (v is V.Str) v.v.compareTo(rhs, ignoreCase = true) else null
                    }
                    c != null && when (op) { ">" -> c > 0; "<" -> c < 0; ">=" -> c >= 0; else -> c <= 0 }
                }
                else -> {
                    val eq = when {
                        rhs.isEmpty() -> v is V.Empty || (v is V.Str && v.v.isEmpty())
                        rn != null && v is V.Num -> v.v == rn
                        v is V.Str -> rx!!.matches(v.v)
                        v is V.Bool -> rhs.equals(if (v.v) "TRUE" else "FALSE", true)
                        else -> false
                    }
                    if (op == "<>") !eq else eq
                }
            }
        }
    }

    private fun area(ev: Evaluator, n: Nd, s: Sheet): V.Area = ev.areaOf(n, s)

    /** Multi-criteria helper for SUMIFS/COUNTIFS/AVERAGEIFS/MAXIFS/MINIFS. Returns matching offsets. */
    private fun matchOffsets(ev: Evaluator, s: Sheet, pairs: List<Nd>, rows: Int, cols: Int): List<Pair<Int, Int>> {
        if (pairs.size % 2 != 0) throw FormulaError("#VALUE!")
        val conds = pairs.chunked(2).map { (rn, cn) ->
            val a = area(ev, rn, s)
            a to criteria(ev, ev.safe(cn, s))
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (dr in 0 until rows) for (dc in 0 until cols) {
            if (conds.all { (a, m) -> m(ev.at(a, dr, dc)) }) out.add(dr to dc)
        }
        return out
    }

    fun call(ev: Evaluator, name: String, args: List<Nd>, s: Sheet): V {
        fun a(i: Int): V = ev.scalar(ev.eval(args[i], s))
        fun n(i: Int): Double = ev.num(a(i))
        fun optN(i: Int, def: Double) = if (args.size > i && args[i] !is NMissing) n(i) else def
        fun t(i: Int): String = ev.str(a(i))

        when (name) {
            "SUM" -> return V.Num(ev.numbers(args, s).sum())
            "AVERAGE" -> {
                val l = ev.numbers(args, s)
                if (l.isEmpty()) throw FormulaError("#DIV/0!")
                return V.Num(l.average())
            }
            "MIN" -> return V.Num(ev.numbers(args, s).minOrNull() ?: 0.0)
            "MAX" -> return V.Num(ev.numbers(args, s).maxOrNull() ?: 0.0)
            "PRODUCT" -> return V.Num(ev.numbers(args, s).fold(1.0) { x, y -> x * y })
            "COUNT" -> {
                var c = 0
                for (ar in args) {
                    val v = ev.eval(ar, s)
                    if (v is V.Area) c += ev.cells(v).count { it is V.Num }
                    else if (v is V.Num) c++
                    else if (ar !is NRef && v is V.Str && Evaluator.parseNumber(v.v) != null) c++
                }
                return V.Num(c.toDouble())
            }
            "COUNTA" -> return V.Num(ev.flatten(args.filter { it !is NMissing }, s).count { it !is V.Empty }.toDouble())
            "COUNTBLANK" -> return V.Num(ev.flatten(args, s).count { it is V.Empty || (it is V.Str && it.v.isEmpty()) }.toDouble())
            "MEDIAN" -> {
                val l = ev.numbers(args, s).sorted()
                if (l.isEmpty()) throw FormulaError("#NUM!")
                return V.Num(if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2)
            }
            "STDEV", "STDEV.S", "STDEV.P", "VAR", "VAR.S", "VAR.P" -> {
                val l = ev.numbers(args, s)
                val pop = name.endsWith(".P")
                val d = if (pop) l.size else l.size - 1
                if (d <= 0) throw FormulaError("#DIV/0!")
                val m = l.average()
                val variance = l.sumOf { (it - m) * (it - m) } / d
                return V.Num(if (name.startsWith("VAR")) variance else sqrt(variance))
            }
            "LARGE", "SMALL" -> {
                need(args, 2)
                val l = ev.numbers(listOf(args[0]), s).sorted()
                val k = n(1).toInt()
                if (k < 1 || k > l.size) throw FormulaError("#NUM!")
                return V.Num(if (name == "SMALL") l[k - 1] else l[l.size - k])
            }
            "RANK", "RANK.EQ" -> {
                val x = n(0)
                val l = ev.numbers(listOf(args[1]), s)
                val asc = optN(2, 0.0) != 0.0
                if (x !in l) throw FormulaError("#N/A")
                return V.Num((if (asc) l.count { it < x } else l.count { it > x }) + 1.0)
            }
            "SUMPRODUCT" -> {
                val areas = args.map { area(ev, it, s) }
                val rows = ev.rows(areas[0])
                val cols = ev.cols(areas[0])
                var total = 0.0
                for (dr in 0 until rows) for (dc in 0 until cols) {
                    var p = 1.0
                    for (ar in areas) { val v = ev.at(ar, dr, dc); p *= if (v is V.Num) v.v else 0.0 }
                    total += p
                }
                return V.Num(total)
            }

            "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                val mode = when (name) { "ROUND" -> RoundingMode.HALF_UP; "ROUNDUP" -> RoundingMode.UP; else -> RoundingMode.DOWN }
                return V.Num(round(n(0), optN(1, 0.0).toInt(), mode))
            }
            "INT" -> return V.Num(floor(n(0)))
            "TRUNC" -> return V.Num(round(n(0), optN(1, 0.0).toInt(), RoundingMode.DOWN))
            "ABS" -> return V.Num(abs(n(0)))
            "SIGN" -> return V.Num(Math.signum(n(0)))
            "SQRT" -> { val x = n(0); if (x < 0) throw FormulaError("#NUM!"); return V.Num(sqrt(x)) }
            "POWER" -> return V.Num(n(0).pow(n(1)))
            "EXP" -> return V.Num(Math.exp(n(0)))
            "LN" -> { val x = n(0); if (x <= 0) throw FormulaError("#NUM!"); return V.Num(ln(x)) }
            "LOG10" -> { val x = n(0); if (x <= 0) throw FormulaError("#NUM!"); return V.Num(Math.log10(x)) }
            "LOG" -> { val x = n(0); val b = optN(1, 10.0); if (x <= 0 || b <= 0) throw FormulaError("#NUM!"); return V.Num(ln(x) / ln(b)) }
            "MOD" -> {
                val x = n(0); val d = n(1)
                if (d == 0.0) throw FormulaError("#DIV/0!")
                return V.Num(x - d * floor(x / d))
            }
            "CEILING", "CEILING.MATH", "FLOOR", "FLOOR.MATH" -> {
                val x = n(0)
                val sig = abs(optN(1, 1.0))
                if (sig == 0.0) return V.Num(0.0)
                val q = x / sig
                return V.Num(if (name.startsWith("CEILING")) Math.ceil(q) * sig else floor(q) * sig)
            }
            "PI" -> return V.Num(Math.PI)
            "RAND" -> return V.Num(Math.random())
            "RANDBETWEEN" -> {
                val lo = Math.ceil(n(0)).toLong(); val hi = floor(n(1)).toLong()
                if (hi < lo) throw FormulaError("#NUM!")
                return V.Num((lo + (Math.random() * (hi - lo + 1)).toLong()).toDouble())
            }

            "PMT", "FV", "PV" -> {
                val rate = n(0); val nper = n(1)
                val type = optN(4, 0.0)
                when (name) {
                    "PMT" -> {
                        val pv = n(2); val fv = optN(3, 0.0)
                        if (rate == 0.0) return V.Num(-(pv + fv) / nper)
                        val f = (1 + rate).pow(nper)
                        return V.Num(-(rate * (fv + pv * f)) / ((1 + rate * type) * (f - 1)))
                    }
                    "FV" -> {
                        val pmt = n(2); val pv = optN(3, 0.0)
                        if (rate == 0.0) return V.Num(-(pv + pmt * nper))
                        val f = (1 + rate).pow(nper)
                        return V.Num(-(pv * f + pmt * (1 + rate * type) * (f - 1) / rate))
                    }
                    else -> {
                        val pmt = n(2); val fv = optN(3, 0.0)
                        if (rate == 0.0) return V.Num(-(fv + pmt * nper))
                        val f = (1 + rate).pow(nper)
                        return V.Num(-(fv + pmt * (1 + rate * type) * (f - 1) / rate) / f)
                    }
                }
            }
            "NPV" -> {
                val rate = n(0)
                val flows = ev.numbers(args.drop(1), s)
                return V.Num(flows.mapIndexed { i, cf -> cf / (1 + rate).pow(i + 1) }.sum())
            }

            "IF" -> {
                need(args, 1, 3)
                val c = ev.bool(a(0))
                if (c) {
                    if (args.size < 2) return V.Bool(true)
                    if (args[1] is NMissing) return V.Num(0.0)
                    return a(1)
                }
                if (args.size < 3) return V.Bool(false)
                if (args[2] is NMissing) return V.Num(0.0)
                return a(2)
            }
            "IFS" -> {
                var i = 0
                while (i + 1 < args.size) {
                    if (ev.bool(a(i))) return a(i + 1)
                    i += 2
                }
                throw FormulaError("#N/A")
            }
            "SWITCH" -> {
                val x = a(0)
                var i = 1
                while (i + 1 < args.size) {
                    if (ev.compare(x, a(i)) == 0 && ev.sameKind(x, a(i))) return a(i + 1)
                    i += 2
                }
                if (i < args.size) return a(i)
                throw FormulaError("#N/A")
            }
            "IFERROR" -> { val v = ev.safe(args[0], s); return if (v is V.Err) a(1) else v }
            "IFNA" -> { val v = ev.safe(args[0], s); return if (v is V.Err && v.code == "#N/A") a(1) else v }
            "AND" -> return V.Bool(ev.flatten(args, s).filter { it !is V.Empty && it !is V.Str }.all { ev.bool(it) })
            "OR" -> return V.Bool(ev.flatten(args, s).filter { it !is V.Empty && it !is V.Str }.any { ev.bool(it) })
            "XOR" -> return V.Bool(ev.flatten(args, s).filter { it !is V.Empty && it !is V.Str }.count { ev.bool(it) } % 2 == 1)
            "NOT" -> return V.Bool(!ev.bool(a(0)))
            "TRUE" -> return V.Bool(true)
            "FALSE" -> return V.Bool(false)
            "ISBLANK" -> return V.Bool(ev.safe(args[0], s) is V.Empty)
            "ISNUMBER" -> return V.Bool(ev.safe(args[0], s) is V.Num)
            "ISTEXT" -> return V.Bool(ev.safe(args[0], s) is V.Str)
            "ISLOGICAL" -> return V.Bool(ev.safe(args[0], s) is V.Bool)
            "ISERROR" -> return V.Bool(ev.safe(args[0], s) is V.Err)
            "ISNA" -> { val v = ev.safe(args[0], s); return V.Bool(v is V.Err && v.code == "#N/A") }
            "NA" -> throw FormulaError("#N/A")
            "CHOOSE" -> {
                val i = n(0).toInt()
                if (i < 1 || i >= args.size) throw FormulaError("#VALUE!")
                return a(i)
            }

            "CONCATENATE", "CONCAT" -> return V.Str(ev.flatten(args, s).joinToString("") { ev.str(it) })
            "TEXTJOIN" -> {
                val sep = t(0); val skip = ev.bool(a(1))
                val parts = ev.flatten(args.drop(2), s).map { ev.str(it) }.filter { !skip || it.isNotEmpty() }
                return V.Str(parts.joinToString(sep))
            }
            "LEN" -> return V.Num(t(0).length.toDouble())
            "LEFT" -> { val x = t(0); val k = optN(1, 1.0).toInt(); if (k < 0) throw FormulaError("#VALUE!"); return V.Str(x.take(k)) }
            "RIGHT" -> { val x = t(0); val k = optN(1, 1.0).toInt(); if (k < 0) throw FormulaError("#VALUE!"); return V.Str(x.takeLast(k)) }
            "MID" -> {
                val x = t(0); val st = n(1).toInt(); val k = n(2).toInt()
                if (st < 1 || k < 0) throw FormulaError("#VALUE!")
                return V.Str(if (st > x.length) "" else x.substring(st - 1, minOf(x.length, st - 1 + k)))
            }
            "UPPER" -> return V.Str(t(0).uppercase())
            "LOWER" -> return V.Str(t(0).lowercase())
            "PROPER" -> return V.Str(t(0).lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } })
            "TRIM" -> return V.Str(t(0).trim().replace(Regex(" +"), " "))
            "REPT" -> return V.Str(t(0).repeat(n(1).toInt().coerceIn(0, 32767)))
            "EXACT" -> return V.Bool(t(0) == t(1))
            "CHAR" -> return V.Str(n(0).toInt().toChar().toString())
            "CODE" -> { val x = t(0); if (x.isEmpty()) throw FormulaError("#VALUE!"); return V.Num(x[0].code.toDouble()) }
            "SUBSTITUTE" -> {
                val x = t(0); val old = t(1); val new = t(2)
                if (old.isEmpty()) return V.Str(x)
                if (args.size > 3) {
                    val which = n(3).toInt()
                    var idx = -1
                    repeat(which) { idx = x.indexOf(old, idx + 1); if (idx < 0) return V.Str(x) }
                    return V.Str(x.substring(0, idx) + new + x.substring(idx + old.length))
                }
                return V.Str(x.replace(old, new))
            }
            "REPLACE" -> {
                val x = t(0); val st = n(1).toInt() - 1; val k = n(2).toInt(); val new = t(3)
                if (st < 0 || k < 0) throw FormulaError("#VALUE!")
                val a0 = minOf(st, x.length); val b0 = minOf(st + k, x.length)
                return V.Str(x.substring(0, a0) + new + x.substring(b0))
            }
            "FIND", "SEARCH" -> {
                val needle = t(0); val hay = t(1); val st = optN(2, 1.0).toInt() - 1
                if (st < 0 || st > hay.length) throw FormulaError("#VALUE!")
                val idx = if (name == "FIND") hay.indexOf(needle, st)
                else wildcard(needle).find(hay, st)?.range?.first ?: -1
                if (idx < 0) throw FormulaError("#VALUE!")
                return V.Num(idx + 1.0)
            }
            "VALUE" -> {
                val v = a(0)
                if (v is V.Num) return v
                return V.Num(Evaluator.parseNumber(ev.str(v)) ?: throw FormulaError("#VALUE!"))
            }
            "TEXT" -> return V.Str(NumFmt.format(n(0), t(1)))

            "TODAY" -> return V.Num(XlDate.toSerial(LocalDate.now()))
            "NOW" -> return V.Num(XlDate.toSerial(LocalDateTime.now()))
            "DATE" -> {
                val y = n(0).toInt(); val m = n(1).toInt(); val d = n(2).toInt()
                val base = LocalDate.of(if (y < 1900) y + 1900 else y, 1, 1).plusMonths((m - 1).toLong()).plusDays((d - 1).toLong())
                return V.Num(XlDate.toSerial(base))
            }
            "TIME" -> return V.Num(((n(0) * 3600 + n(1) * 60 + n(2)) / 86400.0).mod(1.0))
            "YEAR" -> return V.Num(XlDate.fromSerial(n(0)).year.toDouble())
            "MONTH" -> return V.Num(XlDate.fromSerial(n(0)).monthValue.toDouble())
            "DAY" -> return V.Num(XlDate.fromSerial(n(0)).dayOfMonth.toDouble())
            "HOUR" -> return V.Num(XlDate.fromSerial(n(0)).hour.toDouble())
            "MINUTE" -> return V.Num(XlDate.fromSerial(n(0)).minute.toDouble())
            "SECOND" -> return V.Num(XlDate.fromSerial(n(0)).second.toDouble())
            "WEEKDAY" -> {
                val dow = XlDate.fromSerial(n(0)).dayOfWeek
                val type = optN(1, 1.0).toInt()
                val sunday1 = if (dow == DayOfWeek.SUNDAY) 1 else dow.value + 1
                return V.Num(when (type) { 2 -> dow.value; 3 -> dow.value - 1; else -> sunday1 }.toDouble())
            }
            "DAYS" -> return V.Num(floor(n(0)) - floor(n(1)))
            "EDATE" -> return V.Num(XlDate.toSerial(XlDate.fromSerial(n(0)).toLocalDate().plusMonths(n(1).toLong())))
            "EOMONTH" -> {
                val d = XlDate.fromSerial(n(0)).toLocalDate().plusMonths(n(1).toLong())
                return V.Num(XlDate.toSerial(d.withDayOfMonth(d.lengthOfMonth())))
            }

            "SUMIF", "AVERAGEIF" -> {
                val rng = area(ev, args[0], s)
                val m = criteria(ev, ev.safe(args[1], s))
                val sumRng = if (args.size > 2) area(ev, args[2], s) else rng
                val rows = ev.rows(rng); val cols = ev.cols(rng)
                val vals = ArrayList<Double>()
                for (dr in 0 until rows) for (dc in 0 until cols) {
                    if (m(ev.at(rng, dr, dc))) { val v = ev.at(sumRng, dr, dc); if (v is V.Num) vals.add(v.v) }
                }
                if (name == "AVERAGEIF") {
                    if (vals.isEmpty()) throw FormulaError("#DIV/0!")
                    return V.Num(vals.average())
                }
                return V.Num(vals.sum())
            }
            "COUNTIF" -> {
                val rng = area(ev, args[0], s)
                val m = criteria(ev, ev.safe(args[1], s))
                return V.Num(ev.cells(rng).count(m).toDouble())
            }
            "SUMIFS", "AVERAGEIFS", "MAXIFS", "MINIFS" -> {
                val target = area(ev, args[0], s)
                val rows = ev.rows(target); val cols = ev.cols(target)
                val vals = matchOffsets(ev, s, args.drop(1), rows, cols).mapNotNull { (dr, dc) -> (ev.at(target, dr, dc) as? V.Num)?.v }
                return when (name) {
                    "SUMIFS" -> V.Num(vals.sum())
                    "MAXIFS" -> V.Num(vals.maxOrNull() ?: 0.0)
                    "MINIFS" -> V.Num(vals.minOrNull() ?: 0.0)
                    else -> { if (vals.isEmpty()) throw FormulaError("#DIV/0!"); V.Num(vals.average()) }
                }
            }
            "COUNTIFS" -> {
                val first = area(ev, args[0], s)
                return V.Num(matchOffsets(ev, s, args, ev.rows(first), ev.cols(first)).size.toDouble())
            }

            "VLOOKUP", "HLOOKUP" -> {
                need(args, 3, 4)
                val key = a(0)
                val tbl = area(ev, args[1], s)
                val idx = n(2).toInt()
                val approx = if (args.size > 3 && args[3] !is NMissing) ev.bool(a(3)) else true
                val vertical = name == "VLOOKUP"
                val count = if (vertical) ev.rows(tbl) else ev.cols(tbl)
                val span = if (vertical) tbl.c2 - tbl.c1 + 1 else tbl.r2 - tbl.r1 + 1
                if (idx < 1 || idx > span) throw FormulaError("#REF!")
                fun keyAt(i: Int) = if (vertical) ev.at(tbl, i, 0) else ev.at(tbl, 0, i)
                var found = -1
                if (!approx) {
                    val rx = if (key is V.Str) wildcard(key.v) else null
                    for (i in 0 until count) {
                        val v = keyAt(i)
                        val hit = if (rx != null) v is V.Str && rx.matches(v.v) else ev.sameKind(v, key) && ev.compare(v, key) == 0
                        if (hit) { found = i; break }
                    }
                } else {
                    for (i in 0 until count) {
                        val v = keyAt(i)
                        if (v is V.Empty || !ev.sameKind(v, key)) continue
                        if (ev.compare(v, key) <= 0) found = i else break
                    }
                }
                if (found < 0) throw FormulaError("#N/A")
                return if (vertical) ev.at(tbl, found, idx - 1) else ev.at(tbl, idx - 1, found)
            }
            "XLOOKUP" -> {
                val key = a(0)
                val look = area(ev, args[1], s)
                val ret = area(ev, args[2], s)
                val vertical = look.c1 == look.c2
                val count = if (vertical) ev.rows(look) else ev.cols(look)
                for (i in 0 until count) {
                    val v = if (vertical) ev.at(look, i, 0) else ev.at(look, 0, i)
                    if (ev.sameKind(v, key) && ev.compare(v, key) == 0) return if (vertical) ev.at(ret, i, 0) else ev.at(ret, 0, i)
                }
                if (args.size > 3) return a(3)
                throw FormulaError("#N/A")
            }
            "INDEX" -> {
                val tbl = area(ev, args[0], s)
                var r = n(1).toInt()
                var c = optN(2, 1.0).toInt()
                if (args.size == 2 && tbl.r1 == tbl.r2) { c = r; r = 1 }
                if (r < 1 || c < 1 || tbl.r1 + r - 1 > tbl.r2 || tbl.c1 + c - 1 > tbl.c2) throw FormulaError("#REF!")
                return ev.at(tbl, r - 1, c - 1)
            }
            "MATCH" -> {
                val key = a(0)
                val tbl = area(ev, args[1], s)
                val type = optN(2, 1.0).toInt()
                val vals = ev.cells(tbl)
                var found = -1
                when (type) {
                    0 -> {
                        val rx = if (key is V.Str) wildcard(key.v) else null
                        found = vals.indexOfFirst { v -> if (rx != null) v is V.Str && rx.matches(v.v) else ev.sameKind(v, key) && ev.compare(v, key) == 0 }
                    }
                    1 -> for ((i, v) in vals.withIndex()) { if (v is V.Empty || !ev.sameKind(v, key)) continue; if (ev.compare(v, key) <= 0) found = i else break }
                    else -> for ((i, v) in vals.withIndex()) { if (v is V.Empty || !ev.sameKind(v, key)) continue; if (ev.compare(v, key) >= 0) found = i else break }
                }
                if (found < 0) throw FormulaError("#N/A")
                return V.Num(found + 1.0)
            }
            "ROWS" -> { val ar = area(ev, args[0], s); return V.Num((ar.r2 - ar.r1 + 1).toDouble()) }
            "COLUMNS" -> { val ar = area(ev, args[0], s); return V.Num((ar.c2 - ar.c1 + 1).toDouble()) }
            "ROW" -> {
                if (args.isEmpty() || args[0] is NMissing) return V.Num(ev.curRow + 1.0)
                return V.Num(area(ev, args[0], s).r1 + 1.0)
            }
            "COLUMN" -> {
                if (args.isEmpty() || args[0] is NMissing) return V.Num(ev.curCol + 1.0)
                return V.Num(area(ev, args[0], s).c1 + 1.0)
            }
        }
        throw FormulaError("#NAME?")
    }
}

/* ===================== Number formats ===================== */

object NumFmt {
    val BUILTIN: Map<Int, String> = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%",
        11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??", 14 to "m/d/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm",
        17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "m/d/yyyy h:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)",
        40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@"
    )

    /** Formats offered in the app's toolbar. */
    val PRESETS: List<Pair<String, String>> = listOf(
        "General" to "General",
        "Number" to "0.00",
        "Thousands" to "#,##0.00",
        "Integer" to "#,##0",
        "Currency" to "\"$\"#,##0.00",
        "Percent" to "0%",
        "Percent 2" to "0.00%",
        "Date" to "yyyy-mm-dd",
        "Date & time" to "yyyy-mm-dd hh:mm",
        "Time" to "hh:mm",
        "Scientific" to "0.00E+00",
        "Text" to "@",
    )

    fun builtinId(code: String): Int? = BUILTIN.entries.firstOrNull { it.value == code }?.key

    fun general(v: Double): String {
        if (v == 0.0) return "0"
        if (v.isNaN()) return "#NUM!"
        if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
        val a = abs(v)
        if (a >= 1e11 || a < 1e-9) {
            return String.format(Locale.US, "%.5E", v).replace(Regex("\\.?0+E"), "E")
        }
        return BigDecimal(v).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    private fun isDateCode(code: String): Boolean {
        val c = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").lowercase()
        if (c.contains('y') || c.contains('d') || c.contains('h') || c.contains('s')) return !c.contains('0') || c.contains(':')
        return c.contains('m') && !c.contains('0') && !c.contains('#')
    }

    fun isDate(code: String) = code != "General" && code != "@" && isDateCode(code)

    fun format(v: Double, code0: String): String {
        if (code0.isEmpty() || code0.equals("General", true)) return general(v)
        if (code0 == "@") return general(v)
        val sections = splitSections(code0)
        var code = sections[0]
        var x = v
        if (v < 0 && sections.size > 1) { code = sections[1]; x = -v } else if (v == 0.0 && sections.size > 2) code = sections[2]
        code = code.replace(Regex("\\[(Red|Black|Blue|Green|White|Yellow|Cyan|Magenta|Color\\s*\\d+)]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[\\$([^-\\]]*)(-[^]]*)?]"), "\"$1\"")
        if (isDateCode(code)) return formatDate(x, code)
        return formatNumber(x, code, negativeInCode = v < 0 && sections.size > 1)
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (ch in code) {
            if (ch == '"') q = !q
            if (ch == ';' && !q) { out.add(sb.toString()); sb.clear() } else sb.append(ch)
        }
        out.add(sb.toString())
        return out
    }

    private fun formatNumber(v: Double, code: String, negativeInCode: Boolean): String {
        // split into prefix / number pattern / suffix, honoring quoted literals
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        val pat = StringBuilder()
        var i = 0
        var seenDigit = false
        var afterDigits = false
        while (i < code.length) {
            val ch = code[i]
            when {
                ch == '"' -> {
                    val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }
                    val lit = code.substring(i + 1, end)
                    if (seenDigit) { afterDigits = true; suffix.append(lit) } else prefix.append(lit)
                    i = end + 1; continue
                }
                ch == '\\' && i + 1 < code.length -> { if (seenDigit) { afterDigits = true; suffix.append(code[i + 1]) } else prefix.append(code[i + 1]); i += 2; continue }
                ch == '_' || ch == '*' -> { i += 2; continue }
                (ch in "0#?.,") && !afterDigits -> { pat.append(ch); if (ch != '.' && ch != ',') seenDigit = true }
                ch == '%' -> { if (seenDigit) { afterDigits = true; suffix.append('%') } else prefix.append('%') }
                ch == 'E' || ch == 'e' -> {
                    val decimals = pat.toString().substringAfter('.', "").count { it == '0' || it == '#' }
                    return prefix.toString() + String.format(Locale.US, "%.${decimals}E", v).replace("E", "E+").replace("E+-", "E-") + suffix
                }
                else -> if (seenDigit) { afterDigits = true; suffix.append(ch) } else prefix.append(ch)
            }
            i++
        }
        var x = v
        val percents = (prefix.toString() + suffix.toString()).count { it == '%' }
        repeat(percents) { x *= 100 }
        val p = pat.toString()
        // trailing commas scale by 1000
        var scaled = p
        while (scaled.endsWith(",")) { x /= 1000; scaled = scaled.dropLast(1) }
        val decimalsPart = scaled.substringAfter('.', "")
        val minDec = decimalsPart.count { it == '0' }
        val maxDec = decimalsPart.count { it == '0' || it == '#' || it == '?' }
        val grouping = scaled.substringBefore('.').contains(',')
        var s = BigDecimal(x).setScale(maxDec, RoundingMode.HALF_UP).abs().toPlainString()
        if (maxDec > minDec && s.contains('.')) {
            var trimmed = s.trimEnd('0')
            val dec = trimmed.substringAfter('.', "")
            if (dec.length < minDec) trimmed += "0".repeat(minDec - dec.length)
            s = trimmed.trimEnd('.')
        }
        var intPart = s.substringBefore('.')
        val decPart = if (s.contains('.')) "." + s.substringAfter('.') else ""
        if (grouping) intPart = intPart.reversed().chunked(3).joinToString(",").reversed()
        if (intPart == "0" && scaled.substringBefore('.').let { it.isNotEmpty() && !it.contains('0') }) intPart = ""
        val neg = x < 0 && !negativeInCode && (intPart + decPart).any { it in '1'..'9' }
        return (if (neg) "-" else "") + prefix + intPart + decPart + suffix
    }

    private fun formatDate(v: Double, code: String): String {
        val dt = XlDate.fromSerial(v)
        val lower = code.lowercase()
        val ampm = lower.contains("am/pm") || lower.contains("a/p")
        val sb = StringBuilder()
        var i = 0
        var lastWasHour = false
        val src = code.replace(Regex("(?i)am/pm"), "\u0001").replace(Regex("(?i)a/p"), "\u0002")
        while (i < src.length) {
            val ch = src[i]
            val lc = ch.lowercaseChar()
            var j = i
            while (j < src.length && src[j].lowercaseChar() == lc && lc in "ymdhs") j++
            val run = j - i
            when {
                ch == '"' -> {
                    val end = src.indexOf('"', i + 1).let { if (it < 0) src.length else it }
                    sb.append(src, i + 1, end); i = end + 1; continue
                }
                ch == '\\' && i + 1 < src.length -> { sb.append(src[i + 1]); i += 2; continue }
                ch == '\u0001' -> { sb.append(if (dt.hour < 12) "AM" else "PM"); i++; continue }
                ch == '\u0002' -> { sb.append(if (dt.hour < 12) "A" else "P"); i++; continue }
                lc == 'y' -> { sb.append(if (run <= 2) String.format(Locale.US, "%02d", dt.year % 100) else dt.year.toString()); lastWasHour = false }
                lc == 'm' -> {
                    val rest = src.substring(j).lowercase().trimStart(':', ' ')
                    val isMinute = lastWasHour || rest.startsWith("s")
                    if (isMinute && run <= 2) sb.append(if (run == 2) String.format(Locale.US, "%02d", dt.minute) else dt.minute.toString())
                    else sb.append(
                        when (run) {
                            1 -> dt.monthValue.toString()
                            2 -> String.format(Locale.US, "%02d", dt.monthValue)
                            3 -> dt.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
                            else -> dt.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
                        }
                    )
                    lastWasHour = false
                }
                lc == 'd' -> {
                    sb.append(
                        when (run) {
                            1 -> dt.dayOfMonth.toString()
                            2 -> String.format(Locale.US, "%02d", dt.dayOfMonth)
                            3 -> dt.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
                            else -> dt.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
                        }
                    )
                    lastWasHour = false
                }
                lc == 'h' -> {
                    val h = if (ampm) (if (dt.hour % 12 == 0) 12 else dt.hour % 12) else dt.hour
                    sb.append(if (run >= 2) String.format(Locale.US, "%02d", h) else h.toString())
                    lastWasHour = true
                }
                lc == 's' -> { sb.append(if (run >= 2) String.format(Locale.US, "%02d", dt.second) else dt.second.toString()); lastWasHour = false }
                ch == '[' || ch == ']' -> {}
                else -> sb.append(ch)
            }
            i = if (lc in "ymdhs") j else i + 1
        }
        return sb.toString()
    }
}
