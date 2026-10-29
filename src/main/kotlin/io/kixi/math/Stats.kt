package io.kixi.math

import io.kixi.Grid
import io.kixi.uom.Quantity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt
import io.kixi.uom.Unit as KiUnit

/**
 * Lab statistics: the functions RevGrid formulas and KS scripts use on replicate
 * wells and standard series.
 *
 * ## Values in
 *
 * A series is a list (or any iterable, or a grid's cells) of plain numbers, or of
 * quantities of one lab dimension (concentration, volume, mass, amount, molar
 * mass), or text that reads as either. One value counts as a series of one. With
 * quantities, every value is converted to the dimension's lab base unit (M, ℓ, g,
 * mol, Da), and a bare number among them is taken as already in that unit. A
 * missing value (nil) is an error, not skipped.
 *
 * ## Values out
 *
 * Not rounded for display. The arithmetic is decimal (a value is taken at its
 * shortest decimal, `0.412`, not the binary double nearest it), so exact inputs give
 * exact answers: the mean of `0.412`, `0.398` and `0.405` is `0.405`, and the median
 * of `1nM` and `3nM` is `2nM`. A statistic of quantities comes back as a quantity in
 * a readable unit (`52.3nM`, not `5.23E-8M`) to a double's 16 significant digits;
 * one of plain numbers as a Double. Rounding for display is the display's job, or
 * [round]'s.
 *
 * Deviations are sample statistics (n − 1): replicate wells are a sample.
 */
public object Stats {

    /**
     * Arithmetic mean: `mean([1, 2, 4])` is 2.333…; quantities keep their dimension.
     * Of measures (`0.512±0.008`), the mean with its propagated uncertainty, as KQ's
     * `mean` gives it (Ki.Core's `Measure.mean`).
     */
    @JvmStatic
    public fun mean(values: Any?): Any {
        measuresIn(values)?.let { ms ->
            return try { io.kixi.Measure.mean(ms)!! } catch (e: IllegalArgumentException) { throw MathException("mean: ${e.message}") }
        }
        val s = series(values, "values")
        return out(meanOf(s.values), s.scale)
    }

    /** Sample standard deviation (n − 1). Needs at least two values. */
    @JvmStatic
    public fun sd(values: Any?): Any {
        val s = series(values, "values")
        if (s.values.size < 2) throw MathException("sd needs at least 2 values (${s.values.size} given)")
        return out(sdOf(s.values), s.scale)
    }

    /** Coefficient of variation, sd over |mean|, as a percent. */
    @JvmStatic
    public fun cv(values: Any?): Double {
        val s = series(values, "values")
        if (s.values.size < 2) throw MathException("cv needs at least 2 values (${s.values.size} given)")
        val mean = meanOf(s.values)
        if (mean.signum() == 0) throw MathException("cv is undefined when the mean is zero")
        return sdOf(s.values).divide(mean.abs(), MC).multiply(HUNDRED).toDouble()
    }

    /** Standard error of the mean, sd / √n. Needs at least two values. */
    @JvmStatic
    public fun sem(values: Any?): Any {
        val s = series(values, "values")
        if (s.values.size < 2) throw MathException("sem needs at least 2 values (${s.values.size} given)")
        return out(sdOf(s.values).divide(BigDecimal(s.values.size).sqrt(MC), MC), s.scale)
    }

    /**
     * The mean with the sample standard deviation as its uncertainty, a measure:
     * `meanSd([0.412, 0.398, 0.405])` is `0.405±0.007`. Quantities come back in a
     * readable unit, both numbers in it. Needs at least two values.
     */
    @JvmStatic
    public fun meanSd(values: Any?): io.kixi.Measure = withSpread(values, "meanSd", sem = false)

    /**
     * The mean with its standard error (sd / √n) as its uncertainty, a measure: how
     * well the replicates pin the mean down. Needs at least two values.
     */
    @JvmStatic
    public fun meanSem(values: Any?): io.kixi.Measure = withSpread(values, "meanSem", sem = true)

    /**
     * The inverse-variance weighted mean of measures of one quantity (each weighted by
     * 1/σ²), with uncertainty 1/√Σ(1/σ²): the best estimate from independent
     * measurements of different precision. Ki.Core's `Measure.weightedMean`.
     */
    @JvmStatic
    public fun weightedMean(values: Any?): io.kixi.Measure {
        val items = itemsOf(values) ?: if (values == null) throw MathException("Missing measures") else listOf(values)
        if (items.isEmpty()) throw MathException("weightedMean needs at least one measure")
        val ms = items.map { it as? io.kixi.Measure ?: throw MathException("weightedMean takes measures (0.512±0.008), not ${it?.let { x -> x::class.simpleName } ?: "nil"}") }
        return try { io.kixi.Measure.weightedMean(ms)!! } catch (e: IllegalArgumentException) { throw MathException("weightedMean: ${e.message}") }
    }

    private fun withSpread(values: Any?, what: String, sem: Boolean): io.kixi.Measure {
        val measure = measuresIn(values)?.firstOrNull { it is io.kixi.Measure }
        if (measure != null) throw MathException(
            "$what needs plain readings, and these include a measure ($measure). For measures use mean(...), " +
                "which carries their uncertainty, or weightedMean(...), which counts the most precise most"
        )
        val s = series(values, "values")
        if (s.values.size < 2) throw MathException("$what needs at least 2 values (${s.values.size} given)")
        val mean = meanOf(s.values)
        var spread = sdOf(s.values)
        if (sem) spread = spread.divide(BigDecimal(s.values.size).sqrt(MC), MC)
        val scale = s.scale ?: return io.kixi.Measure.of(mean.toDouble(), spread.toDouble())
        val q = scale.fromBase(mean)
        val sigma = spread.multiply(scale.base.factor).divide(q.unit.factor, MC).round(java.math.MathContext.DECIMAL64).stripTrailingZerosPlain()
        return io.kixi.Measure.of(q, Quantity(sigma, q.unit))
    }

    /** The items when any is a measure, else null. */
    private fun measuresIn(values: Any?): List<Any>? {
        val items = itemsOf(values) ?: listOfNotNull(values)
        if (items.none { it is io.kixi.Measure }) return null
        return items.map { it ?: throw MathException("Missing value among measures") }
    }

    /** The middle value (the mean of the two middle values for an even count). */
    @JvmStatic
    public fun median(values: Any?): Any = quantile(values, 0.5)

    /**
     * The [p] quantile, 0 ≤ p ≤ 1, by linear interpolation between order statistics
     * (Hyndman and Fan's type 7: R's default, numpy's, Excel's QUANTILE.INC).
     */
    @JvmStatic
    public fun quantile(values: Any?, p: Any?): Any {
        val q = number(p, "p")
        if (q.isNaN() || q < 0.0 || q > 1.0) throw MathException("p must be between 0 and 1, not $q")
        val s = series(values, "values")
        val sorted = s.values.sortedArray()
        val h = BigDecimal(sorted.size - 1).multiply(decimalOf(q))
        val lo = h.setScale(0, RoundingMode.FLOOR).intValueExact()
        val hi = h.setScale(0, RoundingMode.CEILING).intValueExact()
        val v = sorted[lo] + (h - BigDecimal(lo)).multiply(sorted[hi] - sorted[lo], MC)
        return out(v, s.scale)
    }

    /**
     * [values] minus a blank: one reading, or a series whose mean is used. Returns
     * the corrected list, ready to feed a fit. A blank is read in the values'
     * dimension, so a bare number is in its base unit: `blankSubtract([5nM, 6nM], 1)`
     * subtracts 1 M; write `1nM`. A quantity blank for plain values is an error.
     */
    @JvmStatic
    public fun blankSubtract(values: Any?, blank: Any?): List<Any> {
        val s = series(values, "values")
        val blanks = series(blank, "blank", forced = s.scale)
        if (s.scale == null && blanks.scale != null) {
            throw MathException("The blank is a ${blanks.scale.what} but the values are plain numbers")
        }
        val b = meanOf(blanks.values)
        return s.values.map { out(it - b, s.scale) }
    }

    /**
     * Z-factor of a plate (Zhang 1999), `1 − 3(σp + σn) / |μp − μn|`, over the
     * positive and negative control wells: KQ's `zprime`, to the last bit. Nil values
     * are skipped, as KQ skips them; fewer than two values on either side give nil.
     * Both sides are read in one unit: their common unit, else the base unit.
     */
    @JvmStatic
    public fun zprime(positive: Any?, negative: Any?): Double? {
        val pi = (itemsOf(positive) ?: listOfNotNull(positive)).filterNotNull()
        val ni = (itemsOf(negative) ?: listOfNotNull(negative)).filterNotNull()
        val all = controls(pi + ni, "zprime")
        val p = all.copyOfRange(0, pi.size)
        val n = all.copyOfRange(pi.size, all.size)
        if (p.size < 2 || n.size < 2) return null
        return 1.0 - 3.0 * (sampleSd(p) + sampleSd(n)) / Math.abs(p.average() - n.average())
    }

    /**
     * [x] rounded to [places] decimal places (negative places round to tens,
     * hundreds, …), half away from zero as Excel's ROUND does, not half to even as
     * Kotlin's `round` does: lab users compare with spreadsheets. The result keeps
     * [x]'s kind: a Double stays a Double, a quantity keeps its unit.
     */
    @JvmStatic
    public fun round(x: Any?, places: Any? = 0): Any {
        val n = when (places) {
            null -> 0
            is Int, is Long, is Short, is Byte -> (places as Number).toLong()
            is Number -> places.toDouble().let { if (it % 1.0 != 0.0) throw MathException("places must be a whole number, not $it"); it.toLong() }
            else -> throw MathException("places must be a whole number")
        }
        if (n !in -1000..1000) throw MathException("places must be between -1000 and 1000, not $n")
        return roundValue(x, n.toInt())
    }

    private fun roundValue(x: Any?, places: Int): Any = when (x) {
        is Quantity<*> -> {
            @Suppress("UNCHECKED_CAST")
            Quantity(roundValue(x.value, places) as Number, x.unit as KiUnit)
        }
        is BigDecimal -> x.setScale(places, RoundingMode.HALF_UP).let { if (places < 0) it.setScale(0) else it }
        is Double -> { if (!x.isFinite()) x else decimalOf(x).setScale(places, RoundingMode.HALF_UP).toDouble() }
        is Float -> { if (!x.isFinite()) x else decimalOf(x).setScale(places, RoundingMode.HALF_UP).toFloat() }
        is Int -> if (places >= 0) x else BigDecimal(x).setScale(places, RoundingMode.HALF_UP).intValueExactOr { throw MathException("round($x, $places) does not fit an Int") }
        is Long -> if (places >= 0) x else BigDecimal(x).setScale(places, RoundingMode.HALF_UP).longValueExactOr { throw MathException("round($x, $places) does not fit a Long") }
        is Number -> roundValue(x.toDouble(), places)
        is String -> roundValue(x.trim().toDoubleOrNull() ?: throw MathException("'$x' is not a number"), places)
        null -> throw MathException("Missing value to round")
        else -> throw MathException("Expected a number or a quantity to round, got ${x::class.simpleName}")
    }

    private inline fun BigDecimal.intValueExactOr(fail: () -> Nothing): Int = try { intValueExact() } catch (e: ArithmeticException) { fail() }
    private inline fun BigDecimal.longValueExactOr(fail: () -> Nothing): Long = try { longValueExact() } catch (e: ArithmeticException) { fail() }

    // ------------------------------------------------------------------ series

    /** A resolved series: base-unit decimals, and the scale results go back through (null for plain numbers). */
    internal class Series(val values: Array<BigDecimal>, val scale: LabScale?) {
        val doubles: DoubleArray get() = DoubleArray(values.size) { values[it].toDouble() }
    }

    /**
     * [x] as a [Series]. [forced] pins the scale (a blank is read in its values'
     * dimension); otherwise the series' first quantity picks it.
     */
    internal fun series(x: Any?, what: String, forced: LabScale? = null): Series {
        val items = itemsOf(x) ?: if (x == null) throw MathException("Missing $what") else listOf(x)
        if (items.isEmpty()) throw MathException("$what is empty")
        // The first quantity, or text that reads as one ("1.5nM"), picks the dimension.
        val firstQuantity = items.firstOrNull { it is Quantity<*> } as Quantity<*>?
            ?: items.firstNotNullOfOrNull { (it as? String)?.trim()?.takeIf { t -> t.toBigDecimalOrNull() == null }?.let { t -> Quantity.parseOrNull(t) } }
        val scale = forced ?: firstQuantity?.let { LabScale.of(it) }
        val out = Array(items.size) { i ->
            val item = items[i]
            if ((item is Double && !item.isFinite()) || (item is Float && !item.isFinite())) {
                throw MathException("$what holds a value that is not finite ($item)")
            }
            when {
                item == null -> throw MathException("Missing value in $what")
                scale != null -> scale.toBaseDecimal(item)
                item is Number -> decimalOf(item)
                item is String -> item.trim().toBigDecimalOrNull() ?: throw MathException("'$item' in $what is not a number")
                else -> throw MathException("Expected numbers in $what, got ${item::class.simpleName}")
            }
        }
        return Series(out, scale)
    }

    /** The items of a list, another iterable, an array, a vector or a grid (its cells, row by row); null for anything else. */
    internal fun itemsOf(x: Any?): List<Any?>? = when (x) {
        is List<*> -> x
        is Grid<*> -> x.toList()
        is io.kixi.Vector<*> -> x.toList()
        is Iterable<*> -> x.toList()
        is Array<*> -> x.toList()
        is DoubleArray -> x.toList()
        is IntArray -> x.toList()
        is LongArray -> x.toList()
        else -> null
    }

    /**
     * Control wells for [zprime], as KQ's `Aggregates` reads them, so the two agree to
     * the last bit: nil skipped; plain numbers as doubles; quantities in their common
     * unit when they share one, else converted (`Quantity.convertTo`) to the first one's
     * base unit.
     */
    @Suppress("UNCHECKED_CAST")
    private fun controls(items: List<Any>, what: String): DoubleArray {
        if (items.all { it is Number }) return DoubleArray(items.size) { (items[it] as Number).toDouble() }
        if (items.all { it is Quantity<*> }) {
            val qs = items.map { it as Quantity<*> }
            val first = qs[0].unit
            val unit: KiUnit = if (qs.all { it.unit == first }) first else {
                if (qs.any { it.unit.dimensionName != first.dimensionName } || first is io.kixi.uom.Currency) {
                    throw MathException("$what over quantities of different dimensions or currencies (${qs.map { it.unit.symbol }.distinct().joinToString()})")
                }
                first.baseUnit
            }
            return DoubleArray(qs.size) { val q = qs[it]; (if (q.unit == unit) q.value else (q as Quantity<KiUnit>).convertTo(unit).value).toDouble() }
        }
        throw MathException("$what needs numbers or quantities of one dimension")
    }

    internal fun sampleSd(values: DoubleArray): Double {
        val mean = values.average()
        var ss = 0.0
        for (v in values) ss += (v - mean) * (v - mean)
        return sqrt(ss / (values.size - 1))
    }

    private val MC = java.math.MathContext.DECIMAL128
    private val HUNDRED = BigDecimal(100)

    private fun meanOf(values: Array<BigDecimal>): BigDecimal =
        values.fold(BigDecimal.ZERO) { a, b -> a + b }.divide(BigDecimal(values.size), MC)

    private fun sdOf(values: Array<BigDecimal>): BigDecimal {
        val mean = meanOf(values)
        val ss = values.fold(BigDecimal.ZERO) { a, b -> val d = b - mean; a + d.multiply(d) }
        return ss.divide(BigDecimal(values.size - 1), MC).sqrt(MC)
    }

    /** A base-unit result going out: a readable quantity, or the Double. */
    internal fun out(base: BigDecimal, scale: LabScale?): Any = scale?.fromBase(base) ?: base.toDouble()

    /** A bare number argument. */
    internal fun number(x: Any?, what: String): Double = when (x) {
        is Number -> x.toDouble()
        is String -> x.trim().toDoubleOrNull() ?: throw MathException("'$x' is not a number ($what)")
        is Quantity<*> -> throw MathException("Expected a plain number for $what, got ${x.unit.symbol}")
        null -> throw MathException("Missing $what")
        else -> throw MathException("Expected a number for $what")
    }
}
