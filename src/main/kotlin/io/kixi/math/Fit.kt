package io.kixi.math

import io.kixi.uom.Quantity
import io.kixi.uom.Unit as KiUnit
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * A fitted standard curve: a least-squares line or a four-parameter logistic,
 * built by [Fit.linear] / [Fit.fourPL] (`linfit` and `fit4pl` in KS and KQ), and
 * read with [interp] (reading to x, strictly checked) and [predict] (x to reading).
 *
 * ## The 4PL
 *
 * `y = d + (a − d) / (1 + (x/c)^b)`, with `a` the zero-dose response, `d` the
 * infinite-dose response, `c` the EC50 and `b > 0` the hill slope; ascending and
 * descending curves differ only in whether `a < d`. Fitted by Nelder–Mead on the
 * sum of squared residuals with `b` and `c` log-parameterised (so both stay
 * positive), data-driven starting values and one polish restart. Unweighted.
 *
 * ## Calibration range
 *
 * A fit remembers the span of its standards' x values, and [interp] enforces it:
 * a reading whose inferred x falls outside `[xMin, xMax]` is refused with
 * [FitRangeCode.OUTSIDE_CALIBRATION_RANGE] even when the model could compute it.
 * For a 4PL, a reading at or beyond an asymptote is refused first with
 * [FitRangeCode.OUTSIDE_MODEL_RANGE]. Nothing is clamped.
 *
 * ## Units
 *
 * When the x values were quantities, the fit runs in the dimension's lab base unit
 * (M, ℓ, g, mol or Da) and remembers the dimension, so `interp` answers come back
 * as quantities in a readable unit (`52.3nM`). Readings (y) are plain numbers.
 *
 * ## As a value
 *
 * A fit is a value of the `math` type bag: it is written `.fit(4pl x=M a=… b=…
 * c=52.3nM d=… r2=… n=8 from=1.5625nM to=400nM)`, stored by RevDB, and compared
 * by its exact parameters. Every number is the double the fit computed: the
 * literal writes each one's shortest decimal, so it reads back exactly.
 */
public class Fit internal constructor(
    /** Which model. */
    public val model: Model,
    /** LINEAR: `[slope, intercept]`. FOUR_PL: `[a, b, c, d]`. In the base unit when [x] is a dimension. */
    private val p: DoubleArray,
    private val r2Raw: Double,
    /** How many points the fit was made from. */
    public val n: Int,
    internal val scale: LabScale?,
    private val xMinBase: Double,
    private val xMaxBase: Double,
) {

    /** The two models. [code] is stored; never change one. */
    public enum class Model(public val label: String, internal val code: Int, internal val parameters: List<String>) {
        LINEAR("linear", 1, listOf("slope", "intercept")),
        FOUR_PL("4pl", 2, listOf("a", "b", "c", "d"));
    }

    init {
        if (p.size != model.parameters.size) throw MathException("A ${model.label} fit has ${model.parameters.size} parameters, not ${p.size}")
        if (p.any { !it.isFinite() } || !r2Raw.isFinite() || !xMinBase.isFinite() || !xMaxBase.isFinite()) {
            throw MathException("A fit's parameters must be finite numbers")
        }
        if (n < 1) throw MathException("A fit is made from at least one point, not $n")
        if (xMinBase > xMaxBase) throw MathException("A fit's range must run from its smallest x to its largest")
        if (model == Model.FOUR_PL && (p[1] <= 0.0 || p[2] <= 0.0)) throw MathException("A 4PL fit needs b > 0 and c > 0")
    }

    /** `linear` or `4pl`. */
    public val kind: String get() = model.label

    /** The coefficient of determination. */
    public val r2: Double get() = r2Raw

    /** The x unit the fit runs in (M, ℓ, g, mol or Da), or null when the x values were plain numbers. */
    public val xUnit: KiUnit? get() = scale?.base

    /** The parameters, in the base unit: `[slope, intercept]` or `[a, b, c, d]`. */
    public val parameters: List<Double> get() = p.toList()

    /** The smallest standard x: a readable quantity, or a Double. */
    public val xMin: Any get() = xOut(xMinBase)

    /** The largest standard x. */
    public val xMax: Any get() = xOut(xMaxBase)

    /** The slope of a line (reading per base unit of x), or null for a 4PL. */
    public val slope: Double? get() = if (model == Model.LINEAR) p[0] else null

    /** The intercept of a line, or null for a 4PL. */
    public val intercept: Double? get() = if (model == Model.LINEAR) p[1] else null

    /** A 4PL's EC50 (`c`), as x, or null for a line. */
    public val ec50: Any? get() = if (model == Model.FOUR_PL) xOut(p[2]) else null

    /** A 4PL's hill slope (`b`), or null for a line. */
    public val hill: Double? get() = if (model == Model.FOUR_PL) p[1] else null

    /** A 4PL's lower asymptote, or null for a line. */
    public val bottom: Double? get() = if (model == Model.FOUR_PL) minOf(p[0], p[3]) else null

    /** A 4PL's upper asymptote, or null for a line. */
    public val top: Double? get() = if (model == Model.FOUR_PL) maxOf(p[0], p[3]) else null

    private fun xOut(base: Double): Any = scale?.fromBase(base) ?: base

    // ------------------------------------------------------------------ forward

    private fun predictBase(x: Double): Double = when (model) {
        Model.LINEAR -> p[0] * x + p[1]
        Model.FOUR_PL -> {
            if (x < 0.0) throw MathException("A 4PL is defined for x ≥ 0; got $x")
            val u = if (x == 0.0) 0.0 else (x / p[2]).pow(p[1])
            p[3] + (p[0] - p[3]) / (1.0 + u)
        }
    }

    /**
     * The fitted reading at [x]: a quantity (converted through the fit's dimension),
     * a number (in the base unit when the fit has a dimension), or text for either.
     */
    public fun predict(x: Any?): Double {
        val base = when {
            scale != null -> scale.toBase(x)
            x is Number -> x.toDouble()
            x is String -> x.trim().toDoubleOrNull() ?: throw MathException("'$x' is not a number (x)")
            x is Quantity<*> -> throw MathException("This fit's x values were plain numbers; got ${x.unit.symbol}")
            x == null -> throw MathException("Missing x")
            else -> throw MathException("Expected a number for x, got ${x::class.simpleName}")
        }
        if (!base.isFinite()) throw MathException("x must be a finite number, not $base")
        val y = predictBase(base)
        if (!y.isFinite()) throw MathException("The prediction is not finite")
        return y
    }

    // ------------------------------------------------------------------ inverse

    private fun interpBase(y: Double): Double = when (model) {
        Model.LINEAR -> {
            if (p[0] == 0.0) throw MathException("The fitted slope is zero; the line cannot be inverted")
            (y - p[1]) / p[0]
        }
        Model.FOUR_PL -> {
            val lo = minOf(p[0], p[3])
            val hi = maxOf(p[0], p[3])
            if (y <= lo) throw FitRangeException(FitRangeCode.OUTSIDE_MODEL_RANGE,
                "reading $y is at or below the lower asymptote ($lo); the fitted curve never reaches it")
            if (y >= hi) throw FitRangeException(FitRangeCode.OUTSIDE_MODEL_RANGE,
                "reading $y is at or above the upper asymptote ($hi); the fitted curve never reaches it")
            val u = (p[0] - p[3]) / (y - p[3]) - 1.0        // y strictly between the asymptotes, so u > 0
            p[2] * u.pow(1.0 / p[1])
        }
    }

    /**
     * The x whose fitted reading is [reading], strictly checked: a 4PL reading
     * outside the asymptotes and an inferred x outside the standards' span are
     * [FitRangeException]s, never clamped values. A quantity when the fit's x values
     * were quantities.
     */
    public fun interp(reading: Any?): Any {
        val y = Stats.number(reading, "reading")
        if (!y.isFinite()) throw MathException("The reading must be a finite number, not $y")
        val x = interpBase(y)
        // Round-trip float error at the exact endpoints is not extrapolation; a millionth of the span absorbs it.
        val tol = 1e-6 * (xMaxBase - xMinBase)
        if (x < xMinBase - tol || x > xMaxBase + tol) throw FitRangeException(FitRangeCode.OUTSIDE_CALIBRATION_RANGE,
            "inferred ${xOut(x)} is outside the standards' range (${xOut(xMinBase)} to ${xOut(xMaxBase)}); " +
                    "extend the standards or dilute the sample")
        return xOut(x)
    }

    // ------------------------------------------------------------------ value

    override fun equals(other: Any?): Boolean = other is Fit && other.model == model && other.p.contentEquals(p) &&
            other.r2Raw.equals(r2Raw) && other.n == n && other.scale == scale &&
            other.xMinBase.equals(xMinBase) && other.xMaxBase.equals(xMaxBase)

    override fun hashCode(): Int {
        var h = model.hashCode()
        h = 31 * h + p.contentHashCode()
        h = 31 * h + r2Raw.hashCode()
        h = 31 * h + n
        h = 31 * h + (scale?.code ?: 0)
        h = 31 * h + xMinBase.hashCode()
        return 31 * h + xMaxBase.hashCode()
    }

    /** The literal: `.fit(linear slope=… intercept=… r2=… n=… from=… to=…)`. */
    override fun toString(): String = FitType.format(this)

    internal fun literal(): String = buildString {
        append(".fit(").append(model.label)
        if (scale != null) append(" x=").append(scale.base.symbol)
        for ((i, name) in model.parameters.withIndex()) {
            append(' ').append(name).append('=').append(if (model == Model.FOUR_PL && name == "c") xText(p[i]) else p[i].toString())
        }
        append(" r2=").append(r2Raw)
        append(" n=").append(n)
        append(" from=").append(xText(xMinBase))
        append(" to=").append(xText(xMaxBase))
        append(')')
    }

    /** An x value in the literal: a quantity in a readable unit (exact), or the base-unit number. */
    private fun xText(base: Double): String {
        val s = scale ?: return base.toString()
        val unit = s.unitFor(base)
        val text = s.inUnit(base, unit).toPlainString()
        // A value far outside the ladder would write a long run of zeros; the number is exact too.
        return if (text.length > 40) base.toString() else text + unit.symbol
    }

    internal fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(2 + 4 + 8 * (p.size + 3))
        buf.put(model.code.toByte()).put((scale?.code ?: 0).toByte()).putInt(n)
        for (v in p) buf.putDouble(v)
        buf.putDouble(r2Raw).putDouble(xMinBase).putDouble(xMaxBase)
        return buf.array()
    }

    public companion object {

        /** Least-squares line through the points (at least two, with two distinct x values). */
        @JvmStatic
        public fun linear(xs: DoubleArray, ys: DoubleArray): Fit = linear(xs, ys, null)

        /** Four-parameter logistic, unweighted (at least five points, four distinct non-negative x values, a response spread). */
        @JvmStatic
        public fun fourPL(xs: DoubleArray, ys: DoubleArray): Fit = fourPL(xs, ys, null)

        /** `linfit(x, y)`: x a series of numbers or of quantities of one lab dimension, y plain readings. */
        @JvmStatic
        public fun linfit(x: Any?, y: Any?): Fit {
            val (xs, ys) = fitSeries(x, y)
            return linear(xs.doubles, ys.doubles, xs.scale)
        }

        /** `fit4pl(x, y)`, as [linfit]. */
        @JvmStatic
        public fun fit4pl(x: Any?, y: Any?): Fit {
            val (xs, ys) = fitSeries(x, y)
            return fourPL(xs.doubles, ys.doubles, xs.scale)
        }

        private fun fitSeries(x: Any?, y: Any?): Pair<Stats.Series, Stats.Series> {
            val xs = Stats.series(x, "x")
            val ys = Stats.series(y, "y")
            if (ys.scale != null) throw MathException("y values must be plain readings, not quantities")
            if (xs.values.size != ys.values.size) {
                throw MathException("x and y need the same number of values (${xs.values.size} vs ${ys.values.size})")
            }
            return xs to ys
        }

        internal fun linear(xs: DoubleArray, ys: DoubleArray, scale: LabScale?): Fit {
            if (xs.size != ys.size) throw MathException("x and y need the same number of values (${xs.size} vs ${ys.size})")
            if (xs.size < 2) throw MathException("linfit needs at least 2 points (${xs.size} given)")
            val xMin = xs.min()
            val xMax = xs.max()
            if (xMin == xMax) throw MathException("linfit needs at least two distinct x values")
            val xbar = xs.average()
            val ybar = ys.average()
            var sxx = 0.0
            var sxy = 0.0
            var sst = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - xbar
                val dy = ys[i] - ybar
                sxx += dx * dx
                sxy += dx * dy
                sst += dy * dy
            }
            val slope = sxy / sxx
            val intercept = ybar - slope * xbar
            var sse = 0.0
            for (i in xs.indices) {
                val r = ys[i] - (slope * xs[i] + intercept)
                sse += r * r
            }
            val r2 = if (sst == 0.0) 1.0 else 1.0 - sse / sst
            if (!slope.isFinite() || !intercept.isFinite()) throw MathException("linfit did not produce a finite line; check the data")
            return Fit(Model.LINEAR, doubleArrayOf(slope, intercept), r2, xs.size, scale, xMin, xMax)
        }

        internal fun fourPL(xs: DoubleArray, ys: DoubleArray, scale: LabScale?): Fit {
            val n = xs.size
            if (ys.size != n) throw MathException("x and y need the same number of values ($n vs ${ys.size})")
            if (n < 5) throw MathException("fit4pl needs at least 5 points ($n given)")
            if (xs.any { it < 0.0 }) throw MathException("fit4pl needs non-negative x values")
            if (xs.toSet().size < 4) throw MathException("fit4pl needs at least 4 distinct x values")
            if (xs.none { it > 0.0 }) throw MathException("fit4pl needs positive x values")
            if (ys.min() == ys.max()) throw MathException("fit4pl needs a response spread; every y is ${ys.min()}")

            val order = xs.indices.sortedBy { xs[it] }
            val sx = DoubleArray(n) { xs[order[it]] }
            val sy = DoubleArray(n) { ys[order[it]] }

            // a: response toward zero dose; d: toward infinite dose; c: x where the
            // response crosses halfway (interpolated, geometric-mean fallback); b: 1.
            val a0 = sy.first()
            val d0 = sy.last()
            val half = (a0 + d0) / 2.0
            var c0 = run {
                for (i in 0 until n - 1) {
                    val y1 = sy[i]
                    val y2 = sy[i + 1]
                    if ((y1 - half) * (y2 - half) <= 0.0 && y1 != y2) {
                        val t = (half - y1) / (y2 - y1)
                        val cx = sx[i] + t * (sx[i + 1] - sx[i])
                        if (cx > 0.0) return@run cx
                    }
                }
                exp(sx.filter { it > 0.0 }.map { ln(it) }.average())
            }
            if (c0 <= 0.0) c0 = sx.first { it > 0.0 }

            fun sse(t: DoubleArray): Double {
                val a = t[0]
                val d = t[1]
                val b = exp(t[2].coerceIn(-30.0, 30.0))
                val c = exp(t[3].coerceIn(-700.0, 700.0))
                var s = 0.0
                for (i in 0 until n) {
                    val u = if (sx[i] == 0.0) 0.0 else (sx[i] / c).pow(b)
                    val r = sy[i] - (d + (a - d) / (1.0 + u))
                    s += r * r
                    if (!s.isFinite()) return Double.MAX_VALUE
                }
                return s
            }

            // b and c travel in log space, so both stay positive.
            var best = nelderMead(doubleArrayOf(a0, d0, 0.0, ln(c0)), 4000, ::sse)
            best = nelderMead(best, 2000, ::sse)          // polish restart
            val finalSse = sse(best)
            if (finalSse == Double.MAX_VALUE || !finalSse.isFinite()) throw MathException("fit4pl did not converge; check the data")

            val a = best[0]
            val d = best[1]
            val b = exp(best[2])
            val c = exp(best[3])
            if (a == d) throw MathException("fit4pl collapsed to a flat curve; the data shows no dose response")
            if (!b.isFinite() || !c.isFinite() || b <= 0.0 || c <= 0.0) throw MathException("fit4pl did not converge; check the data")

            val ybar = sy.average()
            var sst = 0.0
            for (y in sy) sst += (y - ybar) * (y - ybar)
            val r2 = if (sst == 0.0) 1.0 else 1.0 - finalSse / sst
            return Fit(Model.FOUR_PL, doubleArrayOf(a, b, c, d), r2, n, scale, xs.min(), xs.max())
        }

        /**
         * Nelder–Mead downhill simplex, the standard α=1 / γ=2 / ρ=σ=½ moves. Returns
         * the best vertex at convergence or after [maxIterations].
         */
        private fun nelderMead(start: DoubleArray, maxIterations: Int, f: (DoubleArray) -> Double): DoubleArray {
            val n = start.size
            val simplex = Array(n + 1) { start.copyOf() }
            for (i in 0 until n) simplex[i + 1][i] += if (start[i] != 0.0) 0.15 * abs(start[i]) else 0.1
            val values = DoubleArray(n + 1) { f(simplex[it]) }

            repeat(maxIterations) {
                val order = values.indices.sortedBy { values[it] }
                val best = order[0]
                val worst = order[n]
                val secondWorst = order[n - 1]
                if (values[worst] - values[best] <= 1e-12 * (abs(values[best]) + 1e-12)) return simplex[best]

                val centroid = DoubleArray(n)
                for (i in 0..n) {
                    if (i == worst) continue
                    for (j in 0 until n) centroid[j] += simplex[i][j]
                }
                for (j in 0 until n) centroid[j] /= n.toDouble()

                fun towardWorst(coef: Double) = DoubleArray(n) { j -> centroid[j] + coef * (simplex[worst][j] - centroid[j]) }

                val reflected = towardWorst(-1.0)
                val fr = f(reflected)
                when {
                    fr < values[best] -> {
                        val expanded = towardWorst(-2.0)
                        val fe = f(expanded)
                        if (fe < fr) { simplex[worst] = expanded; values[worst] = fe }
                        else { simplex[worst] = reflected; values[worst] = fr }
                    }
                    fr < values[secondWorst] -> { simplex[worst] = reflected; values[worst] = fr }
                    else -> {
                        val contracted = towardWorst(0.5)
                        val fc = f(contracted)
                        if (fc < values[worst]) { simplex[worst] = contracted; values[worst] = fc }
                        else {
                            for (i in 0..n) {
                                if (i == best) continue
                                for (j in 0 until n) simplex[i][j] = simplex[best][j] + 0.5 * (simplex[i][j] - simplex[best][j])
                                values[i] = f(simplex[i])
                            }
                        }
                    }
                }
            }
            return simplex[values.indices.minBy { values[it] }]
        }

        /** Reads [bytes] written by [encode] at bag version 1. */
        internal fun decode(bytes: ByteArray): Fit {
            require(bytes.size >= 6) { "A stored fit is at least 6 bytes, not ${bytes.size}" }
            val buf = ByteBuffer.wrap(bytes)
            val model = Model.entries.firstOrNull { it.code == bytes[0].toInt() } ?: throw IllegalArgumentException("Unknown fit model ${bytes[0]}")
            buf.get()
            val sc = buf.get().toInt()
            val scale = if (sc == 0) null else LabScale.of(sc) ?: throw IllegalArgumentException("Unknown fit dimension $sc")
            val n = buf.getInt()
            require(bytes.size == 6 + 8 * (model.parameters.size + 3)) { "A stored ${model.label} fit is ${6 + 8 * (model.parameters.size + 3)} bytes, not ${bytes.size}" }
            val p = DoubleArray(model.parameters.size) { buf.getDouble() }
            return Fit(model, p, buf.getDouble(), n, scale, buf.getDouble(), buf.getDouble())
        }
    }
}
