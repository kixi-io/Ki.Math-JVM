package io.kixi.math

import io.kixi.uom.Quantity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal

/**
 * The statistics against hand-checked values (LabNexus's lab kit tests, which
 * rounded for display; Ki.Math does not, so the checks carry a tolerance), quantity
 * series keeping their dimension, and the errors bad series give.
 */
class StatsTest : FunSpec({

    fun q(text: String): Quantity<*> = Quantity.parse(text)
    fun d(x: Any): Double = (x as Number).toDouble()

    test("mean, sd, cv, sem on a hand-checked set") {
        val values = listOf(2, 4, 4, 4, 5, 5, 7, 9)
        Stats.mean(values) shouldBe 5.0
        d(Stats.sd(values)) shouldBe (2.13809 plusOrMinus 1e-5)          // sample, n−1
        Stats.cv(values) shouldBe (42.7618 plusOrMinus 1e-4)
        d(Stats.sem(values)) shouldBe (0.755929 plusOrMinus 1e-6)
    }

    test("replicate absorbances") {
        val reps = listOf(0.412, 0.398, 0.405)
        d(Stats.mean(reps)) shouldBe (0.405 plusOrMinus 1e-12)
        d(Stats.sd(reps)) shouldBe (0.007 plusOrMinus 1e-12)
        Stats.cv(reps) shouldBe (1.728395 plusOrMinus 1e-6)
    }

    test("quantities keep their dimension and come back readable; cv cancels the unit") {
        Stats.mean(listOf(q("1mM"), q("3mM"))).toString() shouldBe "2mM"
        Stats.sd(listOf(q("1mM"), q("2mM"), q("3mM"))).toString() shouldBe "1mM"
        Stats.cv(listOf(q("1mM"), q("2mM"), q("3mM"))) shouldBe (50.0 plusOrMinus 1e-9)
        Stats.mean(listOf(q("1µM"), q("500nM"))).toString() shouldBe "750nM"
        Stats.mean(listOf(q("2g"), q("500mg"))).toString() shouldBe "1.25g"
        Stats.mean(listOf(q("1mL"), "2mL")).toString() shouldBe "1.5mℓ"
        // A bare number among quantities is in the lab base unit (here M).
        Stats.mean(listOf(q("1mM"), 0.003)).toString() shouldBe "2mM"
        Stats.mean(listOf(q("0nM"), q("0nM"))).toString() shouldBe "0M"
    }

    test("median and quantile (type 7)") {
        Stats.median(listOf(3, 1, 2)) shouldBe 2.0
        Stats.median(listOf(4, 1, 3, 2)) shouldBe 2.5
        Stats.quantile(listOf(1, 2, 3, 4, 5), 0.25) shouldBe 2.0
        Stats.quantile(listOf(1, 2, 3, 4), 0.9) shouldBe (3.7 plusOrMinus 1e-12)
        Stats.quantile(listOf(7), 0.5) shouldBe 7.0
        Stats.quantile(listOf(1, 2), 0) shouldBe 1.0
        Stats.quantile(listOf(1, 2), 1) shouldBe 2.0
        Stats.median(listOf(q("1nM"), q("3nM"))).toString() shouldBe "2nM"
        shouldThrow<MathException> { Stats.quantile(listOf(1), 1.5) }.message shouldContain "between 0 and 1"
    }

    test("blank subtraction, scalar and series blanks") {
        Stats.blankSubtract(listOf(0.512, 0.731), 0.047).map { d(it) }.zip(listOf(0.465, 0.684)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-12) }
        Stats.blankSubtract(listOf(0.512, 0.731), listOf(0.045, 0.052)).map { d(it) }.zip(listOf(0.4635, 0.6825)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-12) }
        Stats.blankSubtract(listOf(q("5nM"), q("7nM")), q("1nM")).map { it.toString() } shouldBe listOf("4nM", "6nM")
        shouldThrow<MathException> { Stats.blankSubtract(listOf(0.5, 0.6), q("0.1nM")) }.message shouldContain "plain numbers"
    }

    test("zprime agrees with KQ's: nil skipped, too few values give nil") {
        Stats.zprime(listOf(100, 102, 98, 101), listOf(10, 12, 9, 11))!! shouldBe (0.899761 plusOrMinus 1e-6)
        Stats.zprime(listOf(100, null, 102), listOf(10, 12)) shouldBe Stats.zprime(listOf(100, 102), listOf(10, 12))
        Stats.zprime(listOf(1), listOf(1, 2)) shouldBe null
        // Both sides in one unit, though the positives mix nM and µM and the negatives do not.
        Stats.zprime(listOf(q("100nM"), q("0.102µM")), listOf(q("10nM"), q("12nM")))!! shouldBe
                (Stats.zprime(listOf(100, 102), listOf(10, 12))!! plusOrMinus 1e-9)
    }

    test("round is half away from zero and keeps the kind of value") {
        Stats.round(2.345, 2) shouldBe 2.35
        Stats.round(-2.345, 2) shouldBe -2.35
        Stats.round(2.5) shouldBe 3.0
        Stats.round(0.125f, 2) shouldBe 0.13f
        Stats.round(BigDecimal("1.005"), 2) shouldBe BigDecimal("1.01")
        Stats.round(1234, -2) shouldBe 1200
        Stats.round(1250L, -2) shouldBe 1300L
        Stats.round(7, 3) shouldBe 7
        Stats.round(BigDecimal("1250"), -2) shouldBe BigDecimal("1300")
        Stats.round(q("12.345mM"), 1).toString() shouldBe "12.3mM"
        Stats.round("2.5") shouldBe 3.0
        shouldThrow<MathException> { Stats.round(1.0, 1.5) }
    }

    context("errors are readable") {
        test("mixed dimensions") { shouldThrow<MathException> { Stats.mean(listOf(q("1mM"), q("1g"))) }.message shouldContain "Expected a concentration, got g" }
        test("a dimension the lab functions do not convert") { shouldThrow<MathException> { Stats.mean(listOf(q("1m"))) }.message shouldContain "not supported" }
        test("empty values") { shouldThrow<MathException> { Stats.mean(emptyList<Any>()) } }
        test("sd, cv, sem of one value") {
            shouldThrow<MathException> { Stats.sd(listOf(1.0)) }
            shouldThrow<MathException> { Stats.cv(listOf(1.0)) }
            shouldThrow<MathException> { Stats.sem(listOf(1.0)) }
        }
        test("text that is not a number, and nil") {
            shouldThrow<MathException> { Stats.mean(listOf("abc")) }
            shouldThrow<MathException> { Stats.mean(listOf(1, null)) }.message shouldContain "Missing value"
            shouldThrow<MathException> { Stats.mean(null) }
        }
        test("a value that is not finite") { shouldThrow<MathException> { Stats.mean(listOf(1.0, Double.NaN)) } }
    }

    test("text that reads as a quantity picks the dimension") {
        Stats.mean(listOf("1.5nM", "2.5nM")).toString() shouldBe "2nM"
        Stats.mean(listOf("1.5", "2.5")) shouldBe 2.0
    }

    test("zprime over quantities agrees with KQ's built-in to the last bit") {
        val r = kotlin.random.Random(11)
        val units = listOf("nM", "µM", "mM")
        io.kixi.revdb.RevDB.inMemory().use { db ->
            db.writeBlocking { createCollection("rev:collection plates") }
            repeat(200) { i ->
                // Units drawn per plate and per well, so some sides share one unit and some mix.
                val plateUnit = units[r.nextInt(3)]
                fun wells(base: Double) = List(4) { q("${"%.3f".format(java.util.Locale.ROOT, base + r.nextDouble() * 5)}${if (r.nextBoolean()) plateUnit else units[r.nextInt(3)]}") }
                val p = wells(100.0)
                val n = wells(10.0)
                db.writeBlocking { insert("plates", io.kixi.kd.Tag("p").apply { setAttribute("i", value = i); setAttribute("pos", value = p); setAttribute("neg", value = n) }) }
                val kq = db.kq("from p in plates where p.i == $i select zprime(p.pos, p.neg)").single().values[0]
                Stats.zprime(p, n) shouldBe kq
            }
        }
    }

    test("one value is a series of one; grids and arrays are series") {
        Stats.mean(4) shouldBe 4.0
        Stats.mean(doubleArrayOf(1.0, 3.0)) shouldBe 2.0
        Stats.mean(io.kixi.Grid.fromRows(listOf(listOf(1, 3)))) shouldBe 2.0
    }

    // ---------------------------------------------------------------- measures (TB7)

    fun m(text: String) = io.kixi.Measure.parse(text)

    test("meanSd and meanSem: the mean with its spread as a measure") {
        val reps = listOf(0.412, 0.398, 0.405)
        val sd = Stats.meanSd(reps)
        d(sd.value) shouldBe (0.405 plusOrMinus 1e-12)
        d(sd.uncertainty) shouldBe (0.007 plusOrMinus 1e-12)
        val sem = Stats.meanSem(reps)
        d(sem.uncertainty) shouldBe (0.007 / Math.sqrt(3.0) plusOrMinus 1e-12)
        // quantities: both numbers in one readable unit
        val c = Stats.meanSd(listOf(q("1mM"), q("2mM"), q("3mM")))
        c.toString() shouldBe "2±1mM"
        Stats.meanSd(listOf(q("1µM"), q("500nM"))).unit!!.symbol shouldBe "nM"
        shouldThrow<MathException> { Stats.meanSd(listOf(1.0)) }.message!! shouldContain "at least 2"
        shouldThrow<MathException> { Stats.meanSd(listOf(m("1±0.1"), m("2±0.1"))) }.message!! shouldContain "propagates"
    }

    test("mean of measures propagates as KQ's mean does; weightedMean weights by 1/σ²") {
        val xs = listOf(m("10±3"), m("20±4"))
        Stats.mean(xs) shouldBe io.kixi.Measure.mean(xs)
        Stats.mean(xs) shouldBe m("15.0±2.5")
        val w = Stats.weightedMean(listOf(m("10.0±1.0"), m("12.0±2.0")))
        d(w.value) shouldBe (10.4 plusOrMinus 1e-12)
        d(w.uncertainty) shouldBe (Math.sqrt(0.8) plusOrMinus 1e-12)
        shouldThrow<MathException> { Stats.weightedMean(listOf(1.0, 2.0)) }.message!! shouldContain "measures"
        shouldThrow<MathException> { Stats.weightedMean(listOf(m("1±0"), m("2±1"))) }
    }
})
