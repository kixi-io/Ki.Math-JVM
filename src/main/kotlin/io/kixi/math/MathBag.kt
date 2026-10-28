package io.kixi.math

import io.kixi.text.ParseException
import io.kixi.types.BagFunction
import io.kixi.types.Member
import io.kixi.types.TypeBag
import io.kixi.types.TypeBags
import io.kixi.types.ValueType
import io.kixi.uom.Quantity
import kotlin.reflect.KClass

/**
 * The `math` type bag: lab fits and statistics for KD, KS, KQ and RevDB (Ki type
 * bags design, 5.1).
 *
 * - Type `Fit`, literal `.fit(…)`: a standard curve, linear or 4PL ([Fit]).
 * - Functions `mean`, `sd`, `cv`, `sem`, `median`, `quantile`, `blankSubtract`,
 *   `zprime`, `round`, and the measures `meanSd`, `meanSem`, `weightedMean`
 *   ([Stats]); `linfit`, `fit4pl`, `interp`, `predict` ([Fit]).
 *
 * The bag is found on the classpath through `META-INF/services`; nothing registers
 * it. Its Kotlin API is [Stats] and [Fit]. In KS, `use ki.math` binds the bag
 * (`math.mean(xs)`), `use ki.math.*` binds every function. In KQ the functions are
 * there without an import; KQ's own `mean` and `zprime` come first.
 */
public class MathBag : TypeBag {
    override val name: String get() = NAME
    override val version: Int get() = VERSION
    override val types: List<ValueType<*>> get() = TYPES
    override val functions: List<BagFunction> get() = FUNCTIONS

    public companion object {
        /** The bag's name: `use ki.math`, and its values' bag in RevDB. */
        public const val NAME: String = "math"

        /** The codec version written with every stored value. */
        public const val VERSION: Int = 1

        private val TYPES: List<ValueType<*>> = listOf(FitType)

        private val FUNCTIONS: List<BagFunction> = listOf(
            BagFunction("mean", 1..1) { a -> Stats.mean(a[0]) },
            BagFunction("sd", 1..1) { a -> Stats.sd(a[0]) },
            BagFunction("cv", 1..1, "Double") { a -> Stats.cv(a[0]) },
            BagFunction("sem", 1..1) { a -> Stats.sem(a[0]) },
            BagFunction("meanSd", 1..1, "Measure") { a -> Stats.meanSd(a[0]) },
            BagFunction("meanSem", 1..1, "Measure") { a -> Stats.meanSem(a[0]) },
            BagFunction("weightedMean", 1..1, "Measure") { a -> Stats.weightedMean(a[0]) },
            BagFunction("median", 1..1) { a -> Stats.median(a[0]) },
            BagFunction("quantile", 2..2) { a -> Stats.quantile(a[0], a[1]) },
            BagFunction("blankSubtract", 2..2, "List") { a -> Stats.blankSubtract(a[0], a[1]) },
            BagFunction("zprime", 2..2, "Double") { a -> Stats.zprime(a[0], a[1]) },
            BagFunction("round", 1..2) { a -> Stats.round(a[0], a.getOrNull(1) ?: 0) },
            BagFunction("linfit", 2..2, "Fit") { a -> Fit.linfit(a[0], a[1]) },
            BagFunction("fit4pl", 2..2, "Fit") { a -> Fit.fit4pl(a[0], a[1]) },
            BagFunction("interp", 2..2) { a -> fitOf(a[0]).interp(a[1]) },
            BagFunction("predict", 2..2, "Double") { a -> fitOf(a[0]).predict(a[1]) },
        )

        private fun fitOf(x: Any?): Fit = x as? Fit ?: throw MathException(
            "Expected a fit from linfit or fit4pl, got " + (x?.let { it::class.simpleName } ?: "nil"))
    }
}

/**
 * `Fit`, the `.fit(…)` literal. The inside is the model, then `name=value` pairs:
 *
 * ```
 * .fit(linear slope=0.0124 intercept=0.051 r2=0.9987 n=8 from=0.0 to=40.0)
 * .fit(4pl x=M a=0.048 b=1.21 c=52.3nM d=2.11 r2=0.9991 n=8 from=1.5625nM to=400nM)
 * ```
 *
 * `x=` names the lab base unit of the x dimension (M, ℓ, g, mol, Da) when the
 * standards were quantities; `c`, `from` and `to` are x values, written as
 * quantities of that dimension (or plain numbers in its base unit). Every other
 * value is a plain number, `slope` per base unit of x. Pairs may come in any order.
 */
public object FitType : ValueType<Fit> {
    override val name: String = "Fit"
    override val kotlinClass: KClass<Fit> = Fit::class
    override val literals: List<String> = listOf("fit")

    override fun parse(literal: String): Fit {
        val inside = TypeBags.stringOrToken(TypeBags.inside(literal))
        val words = inside.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val model = Fit.Model.entries.firstOrNull { it.label.equals(words.firstOrNull(), ignoreCase = true) }
            ?: throw ParseException("A .fit literal starts with linear or 4pl: $literal")
        val pairs = LinkedHashMap<String, String>()
        for (w in words.drop(1)) {
            val eq = w.indexOf('=')
            if (eq <= 0 || eq == w.length - 1) throw ParseException("Expected name=value in .fit, not '$w'")
            if (pairs.put(w.substring(0, eq), w.substring(eq + 1)) != null) throw ParseException("'${w.substring(0, eq)}' is given twice in .fit")
        }
        val scale = pairs.remove("x")?.let {
            LabScale.forBaseSymbol(it) ?: throw ParseException("x=$it is not a lab base unit (M, ℓ, g, mol or Da)")
        }
        fun take(key: String): String = pairs.remove(key) ?: throw ParseException("A ${model.label} .fit needs $key=")
        fun number(key: String): Double {
            val t = take(key)
            val d = t.toDoubleOrNull() ?: throw ParseException("$key=$t is not a number")
            if (!d.isFinite()) throw ParseException("$key=$t is not a finite number")
            return d
        }
        fun xValue(key: String): Double {
            val t = take(key)
            t.toDoubleOrNull()?.let { if (it.isFinite()) return it }
            if (scale == null) throw ParseException("$key=$t: a fit whose x values are quantities says so with x= (x=M, x=ℓ, …)")
            val q = try { Quantity.parse(t) } catch (e: Exception) { throw ParseException("$key=$t is not a number or a ${scale.what}") }
            if (!scale.dimension.isInstance(q.unit)) throw ParseException("$key=$t is not a ${scale.what}")
            return q.unit.convertValue(decimalOf(q.value), scale.base).toDouble()
        }
        val p = model.parameters.map { if (model == Fit.Model.FOUR_PL && it == "c") xValue(it) else number(it) }.toDoubleArray()
        val r2 = number("r2")
        val nText = take("n")
        val n = nText.toIntOrNull() ?: throw ParseException("n=$nText is not a whole number")
        val from = xValue("from")
        val to = xValue("to")
        if (pairs.isNotEmpty()) throw ParseException("Unknown in a ${model.label} .fit: ${pairs.keys.joinToString()}")
        return try { Fit(model, p, r2, n, scale, from, to) } catch (e: MathException) { throw ParseException(e.message) }
    }

    override fun format(value: Fit): String = value.literal()

    override fun encode(value: Fit): ByteArray = value.encode()

    override fun decode(bytes: ByteArray, version: Int): Fit {
        require(version == 1) { "Fit version $version" }
        return Fit.decode(bytes)
    }

    override val members: List<Member<Fit>> = listOf(
        Member("kind", null, "String") { f, _ -> f.kind },
        Member("r2", null, "Double") { f, _ -> f.r2 },
        Member("n", null, "Int") { f, _ -> f.n },
        Member("xMin", null) { f, _ -> f.xMin },
        Member("xMax", null) { f, _ -> f.xMax },
        Member("slope", null, "Double") { f, _ -> f.slope },
        Member("intercept", null, "Double") { f, _ -> f.intercept },
        Member("ec50", null) { f, _ -> f.ec50 },
        Member("hill", null, "Double") { f, _ -> f.hill },
        Member("bottom", null, "Double") { f, _ -> f.bottom },
        Member("top", null, "Double") { f, _ -> f.top },
        Member("parameters", null, "List") { f, _ -> f.parameters },
        Member("predict", 1..1, "Double") { f, a -> f.predict(a[0]) },
        Member("interp", 1..1) { f, a -> f.interp(a[0]) },
    )

    override val samples: List<Fit> by lazy {
        val nM = DoubleArray(8) { 400e-9 / Math.pow(2.0, it.toDouble()) }        // a 2-fold series from 400nM
        val absorb = doubleArrayOf(2.05, 1.98, 1.71, 1.21, 0.69, 0.34, 0.16, 0.09)
        listOf(
            Fit.linear(doubleArrayOf(0.0, 10.0, 20.0, 40.0), doubleArrayOf(0.051, 0.175, 0.302, 0.548)),
            Fit.linear(doubleArrayOf(1.0, 2.0), doubleArrayOf(-3.0, -7.5)),                       // negative slope and intercept
            Fit.linear(nM, absorb, LabScale.CONCENTRATION),
            Fit.fourPL(nM, absorb, LabScale.CONCENTRATION),
            Fit.fourPL(doubleArrayOf(0.0, 1.0, 3.0, 10.0, 30.0, 100.0), doubleArrayOf(0.1, 0.12, 0.3, 0.8, 1.4, 1.6)),
            Fit(Fit.Model.LINEAR, doubleArrayOf(1e-300, -0.0), 0.5, 2, LabScale.VOLUME, 1e-300, 1e300),     // far outside every ladder
            Fit(Fit.Model.FOUR_PL, doubleArrayOf(2.0, 0.5, 2.5e-9, 0.1), 0.99, 6, LabScale.MASS, 0.0, 1e-6),
            Fit(Fit.Model.LINEAR, doubleArrayOf(3.0, 1.0), 1.0, 2, LabScale.MOLAR_MASS, 18.015, 58.44),
            Fit(Fit.Model.LINEAR, doubleArrayOf(3.0, 1.0), 1.0, 2, LabScale.AMOUNT, 1e-12, 0.5),
        )
    }
}
