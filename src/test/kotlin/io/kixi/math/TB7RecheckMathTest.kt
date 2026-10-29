package io.kixi.math

import io.kixi.Measure
import io.kixi.revdb.RevDB
import io.kixi.uom.Quantity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.Random

/**
 * TB7 re-check (2026-10-29), Ki.Math: meanSd, meanSem, weightedMean and mean of measures
 * (type bags design 5.1), against the formulas, against KQ's mean, and in KS.
 */
class TB7RecheckMathTest : FunSpec({

    fun m(text: String) = Measure.parse(text)
    fun q(text: String): Quantity<*> = Quantity.parse(text) as Quantity<*>
    fun amt(x: Any): Double = (((x as? Quantity<*>)?.value ?: x) as Number).toDouble()
    fun close(a: Double, b: Double, rel: Double = 1e-12) = Math.abs(a - b) <= rel * maxOf(1e-300, Math.abs(b))

    fun report(name: String, problems: List<String>) {
        if (problems.isNotEmpty()) println("[$name] ${problems.size} problems:\n  " + problems.take(12).joinToString("\n  "))
        withClue(problems.take(12).joinToString("\n")) { problems.size shouldBe 0 }
    }

    test("M1 meanSd and meanSem against the sample deviation, for numbers and quantities; sd and meanSd agree") {
        val rnd = Random(61)
        val problems = ArrayList<String>()
        repeat(300) {
            val n = 2 + rnd.nextInt(6)
            val xs = List(n) { BigDecimal.valueOf(rnd.nextInt(100000).toLong(), rnd.nextInt(5)).toDouble() }
            val mean = xs.average()
            val sd = Math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / (n - 1))
            val a = Stats.meanSd(xs); val b = Stats.meanSem(xs)
            if (!close(amt(a.value), mean) || !close(amt(a.uncertainty), sd, 1e-9)) problems += "meanSd($xs) = $a (mean $mean, sd $sd)"
            if (!close(amt(b.uncertainty), sd / Math.sqrt(n.toDouble()), 1e-9)) problems += "meanSem($xs) = $b"
            if (amt(a.uncertainty) != (Stats.sd(xs) as Number).toDouble()) problems += "meanSd($xs).uncertainty ${a.uncertainty} != sd ${Stats.sd(xs)}"
            // quantities in nM and µM
            val qs = xs.mapIndexed { i, x -> if (i % 2 == 0) Quantity(BigDecimal(x.toString()), io.kixi.uom.Unit.getUnit("nM")!!) else Quantity(BigDecimal(x.toString()).movePointLeft(3), io.kixi.uom.Unit.getUnit("µM")!!) }
            val c = Stats.meanSd(qs)
            val inNm = c.to(io.kixi.uom.Unit.getUnit("nM")!!)
            if (!close(amt(inNm.value), mean, 1e-12) || !close(amt(inNm.uncertainty), sd, 1e-9)) problems += "meanSd($qs) = $c"
            val sdq = Stats.sd(qs) as Quantity<*>
            val sdInNm = amt((sdq as Quantity<io.kixi.uom.Unit>).convertTo(io.kixi.uom.Unit.getUnit("nM")!!))
            if (!close(sdInNm, amt(inNm.uncertainty), 1e-14)) problems += "sd($qs) = $sdq but meanSd's uncertainty is ${c.uncertainty}"
        }
        report("M1", problems)
    }

    test("M2 mean of measures is KQ's mean, value for value, for random measures in mixed units") {
        val rnd = Random(62)
        val problems = ArrayList<String>()
        val units = listOf("nM", "µM", "pM", "mM")
        RevDB.inMemory().use { db ->
            repeat(100) {
                val ms = List(1 + rnd.nextInt(5)) { m("${rnd.nextInt(500)}.${rnd.nextInt(10)}±${rnd.nextInt(50)}.${rnd.nextInt(10)}${units[rnd.nextInt(units.size)]}") }
                val kq = db.kq("from x in [1] select mean(\$ms)", mapOf("ms" to ms)).single().values[0]
                val math = Stats.mean(ms)
                if (kq != math) problems += "mean($ms): KQ $kq, Ki.Math $math"
            }
            // nil among measures: KQ skips it (an aggregate over documents); Ki.Math refuses it, as it refuses nil
            // in any series (type bags design 5.1): a missing replicate is the caller's call.
            val withNil = listOf(m("1±0.1"), null, m("3±0.1"))
            val kq = db.kq("from x in [1] select mean(\$ms)", mapOf("ms" to withNil)).single().values[0]
            if (kq != m("2.0±0.07071067811865475")) problems += "KQ mean with nil: $kq"
            val math = try { "returned ${Stats.mean(withNil)}" } catch (e: MathException) { "refused" }
            if (math != "refused") problems += "Ki.Math mean with nil $math"
            // a plain number among measures (exact)
            val mixed = listOf(m("1±0.1"), 3)
            val kq2 = db.kq("from x in [1] select mean(\$ms)", mapOf("ms" to mixed)).single().values[0]
            val math2 = try { Stats.mean(mixed) } catch (e: Exception) { "error: ${e.message}" }
            if (kq2 != math2) problems += "mean with an exact number: KQ $kq2, Ki.Math $math2"
        }
        report("M2", problems)
    }

    test("M3 weightedMean: the formula, one measure, units, refusals") {
        val problems = ArrayList<String>()
        val w = Stats.weightedMean(listOf(m("1.0±0.1µM"), m("1200±200nM")))
        val w1 = 1 / 0.01; val w2 = 1 / 0.04
        val mean = (1.0 * w1 + 1.2 * w2) / (w1 + w2)
        val inUm = w.to(io.kixi.uom.Unit.getUnit("µM")!!)
        if (!close(amt(inUm.value), mean, 1e-12) || !close(amt(inUm.uncertainty), 1 / Math.sqrt(w1 + w2), 1e-12)) problems += "weightedMean = $w (expected $mean±${1 / Math.sqrt(w1 + w2)} µM)"
        if (Stats.weightedMean(listOf(m("5±1cm"))) != m("5±1cm")) problems += "one measure"
        for (bad in listOf(listOf(m("1±0"), m("2±1")), listOf(1.0, 2.0), listOf(m("1±0.1cm"), m("1±0.1g")), listOf(m("1±0.1"), m("1±0.1cm")), emptyList())) {
            try { val r = Stats.weightedMean(bad); problems += "weightedMean($bad) = $r" } catch (e: MathException) { } catch (e: Exception) { problems += "weightedMean($bad): ${e::class.simpleName}" }
        }
        report("M3", problems)
    }

    test("M4 meanSd and meanSem refuse measures, fewer than two values, nil; vectors as a series") {
        val problems = ArrayList<String>()
        for (bad in listOf(listOf(m("1±0.1"), 2.0), listOf(1.0), listOf(1.0, null), emptyList<Any>())) {
            try { val r = Stats.meanSd(bad); problems += "meanSd($bad) = $r" } catch (e: MathException) { } catch (e: Exception) { problems += "meanSd($bad): ${e::class.simpleName}" }
        }
        // The refusal names the measure it found and the two functions that take measures.
        val said = try { Stats.meanSem(listOf(0.4, m("4.2±0.25nM"))); "no error" } catch (e: MathException) { e.message ?: "" }
        if (!said.startsWith("meanSem needs plain readings, and these include a measure (4.2±0.25nM)") ||
            "mean(...)" !in said || "weightedMean(...)" !in said) problems += "meanSem's refusal of a measure: $said"
        val v = io.kixi.Vector.ofDoubles(1.0, 2.0, 4.0)
        val r = try { Stats.mean(v).toString() } catch (e: Exception) { "error: ${e.message}" }
        if (r.startsWith("error")) problems += "mean(.vec<Double> { 1 2 4 }): $r (KQ's mean(v) reads a vector's numbers)"
        report("M4", problems)
    }

    test("M5 KS: meanSd, meanSem, weightedMean and mean of measures through use ki.math") {
        val problems = ArrayList<String>()
        val cases = linkedMapOf(
            "use ki.math.*\nsay meanSd([0.412, 0.398, 0.405])" to "0.405±0.007",
            "use ki.math.*\nsay mean([10±3, 20±4])" to "15.0±2.5",
            "use ki.math.*\nsay weightedMean([10.0±1.0, 12.0±2.0])" to "10.4±0.8944271909999159",
            "use ki.math.*\nsay meanSd([1mM, 2mM, 3mM])" to "2±1mM",
        )
        for ((src, want) in cases) {
            val got = try { ks(src) } catch (e: Throwable) { "ERR ${e.message}" }
            if (got != want) problems += "${src.replace("\n", "; ")} -> $got (expected $want)"
        }
        report("M5", problems)
    }
})
