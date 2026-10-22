package io.kixi.math

import io.kixi.uom.Quantity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.math.pow

/**
 * Linear fits against closed-form checks, 4PL recovery of exact synthetic
 * parameters, the strict interp contract (model range, then calibration range,
 * never a clamp), quantity x axes, and the fit as a value (literal, encoding).
 * Ported from LabNexus's lab kit tests.
 */
class FitTest : FunSpec({

    fun q(text: String): Quantity<*> = Quantity.parse(text)
    fun molar(x: Any?): Double = LabScale.CONCENTRATION.toBase(x)
    /** A 2-fold series of [n] concentrations from [start] molar. */
    fun serial(start: Double, n: Int): List<Quantity<*>> = List(n) { LabScale.CONCENTRATION.fromBase(start / 2.0.pow(it)) }

    context("linfit") {
        test("a perfect line is recovered exactly") {
            val fit = Fit.linfit(listOf(1, 2, 3, 4), listOf(3.0, 5.0, 7.0, 9.0))
            fit.slope shouldBe 2.0
            fit.intercept shouldBe 1.0
            fit.r2 shouldBe 1.0
            fit.kind shouldBe "linear"
            fit.ec50 shouldBe null
        }
        test("a noisy line, hand-checked") {
            val fit = Fit.linfit(listOf(1, 2, 4, 8), listOf(0.11, 0.24, 0.50, 0.98))
            fit.slope!! shouldBe (0.124087 plusOrMinus 1e-6)
            fit.intercept!! shouldBe (-0.00782609 plusOrMinus 1e-8)
            fit.r2 shouldBe (0.9996 plusOrMinus 1e-4)
            (fit.interp(0.4) as Double) shouldBe (3.28662 plusOrMinus 1e-5)
            fit.predict(3) shouldBe (0.3644 plusOrMinus 1e-4)
        }
        test("interp refuses an inferred x beyond the standards") {
            val fit = Fit.linfit(listOf(1, 2, 4, 8), listOf(0.11, 0.24, 0.50, 0.98))
            shouldThrow<FitRangeException> { fit.interp(5.0) }.code shouldBe FitRangeCode.OUTSIDE_CALIBRATION_RANGE
        }
        test("degenerate inputs are refused") {
            shouldThrow<MathException> { Fit.linfit(listOf(1), listOf(1.0)) }
            shouldThrow<MathException> { Fit.linfit(listOf(2, 2), listOf(1.0, 2.0)) }
            shouldThrow<MathException> { Fit.linfit(listOf(1, 2, 3), listOf(1.0, 2.0)) }
            shouldThrow<MathException> { Fit.linfit(listOf(1, 2), listOf(q("1g"), q("2g"))) }.message shouldContain "plain readings"
        }
        test("a reading or an x that is not finite is an error, not NaN") {
            val plain = Fit.linfit(listOf(1, 2), listOf(1.0, 2.0))
            shouldThrow<MathException> { plain.interp(Double.NaN) }
            shouldThrow<MathException> { plain.predict(Double.NaN) }
            shouldThrow<MathException> { Fit.linfit(listOf(q("1nM"), q("2nM")), listOf(1.0, 2.0)).predict(Double.NaN) }
        }
        test("a plain-number fit refuses a quantity x") {
            shouldThrow<MathException> { Fit.linfit(listOf(1, 2), listOf(1.0, 2.0)).predict(q("1nM")) }
        }
    }

    context("fit4pl") {
        // Exact 4PL data: a=0.02, d=1.1, c=25, b=1.3.
        val xs = listOf(1.0, 3.0, 10.0, 30.0, 100.0, 300.0, 1000.0)
        val ys = xs.map { 1.1 + (0.02 - 1.1) / (1.0 + (it / 25.0).pow(1.3)) }

        test("exact data recovers the parameters") {
            val fit = Fit.fit4pl(xs, ys)
            (fit.ec50 as Double) shouldBe (25.0 plusOrMinus 0.01)
            fit.hill!! shouldBe (1.3 plusOrMinus 0.001)
            fit.bottom!! shouldBe (0.02 plusOrMinus 1e-4)
            fit.top!! shouldBe (1.1 plusOrMinus 1e-4)
            fit.r2 shouldBe (1.0 plusOrMinus 1e-6)
            fit.slope shouldBe null
        }
        test("interp round-trips predict") {
            val fit = Fit.fit4pl(xs, ys)
            (fit.interp(fit.predict(40)) as Double) shouldBe (40.0 plusOrMinus 0.05)
        }
        test("a reading outside the asymptotes is OUTSIDE_MODEL_RANGE") {
            val fit = Fit.fit4pl(xs, ys)
            shouldThrow<FitRangeException> { fit.interp(2.0) }.code shouldBe FitRangeCode.OUTSIDE_MODEL_RANGE
            shouldThrow<FitRangeException> { fit.interp(0.01) }.code shouldBe FitRangeCode.OUTSIDE_MODEL_RANGE
        }
        test("inside the asymptotes but past the standards is OUTSIDE_CALIBRATION_RANGE") {
            val fit = Fit.fit4pl(xs, ys)
            val e = shouldThrow<FitRangeException> { fit.interp(fit.predict(2000)) }
            e.code shouldBe FitRangeCode.OUTSIDE_CALIBRATION_RANGE
            e.message shouldStartWith "OUTSIDE_CALIBRATION_RANGE: "
        }
        test("degenerate inputs are refused") {
            shouldThrow<MathException> { Fit.fit4pl(listOf(1, 2, 3, 4), listOf(1.0, 2.0, 3.0, 4.0)) }
            shouldThrow<MathException> { Fit.fit4pl(listOf(1, 2, 3, 4, 5), listOf(1.0, 1.0, 1.0, 1.0, 1.0)) }
            shouldThrow<MathException> { Fit.fit4pl(listOf(-1, 2, 3, 4, 5), listOf(1.0, 2.0, 3.0, 4.0, 5.0)) }
        }
    }

    context("quantity x axes") {
        val concs = serial(400e-9, 8)
        val linStd = listOf(0.852, 0.448, 0.252, 0.148, 0.101, 0.077, 0.061, 0.056)
        val elisaStd = listOf(0.972, 0.893, 0.745, 0.552, 0.351, 0.211, 0.128, 0.085)

        test("the series reads as written") {
            concs.map { it.toString() } shouldBe listOf("400nM", "200nM", "100nM", "50nM", "25nM", "12.5nM", "6.25nM", "3.125nM")
        }
        test("linfit over a dilution series answers in readable units") {
            val fit = Fit.linfit(concs, linStd)
            val x = fit.interp(0.3) as Quantity<*>
            x.unit.symbol shouldBe "nM"
            molar(x) shouldBe (124.8827e-9 plusOrMinus 1e-13)
            fit.predict(q("100nM")) shouldBe (0.2502 plusOrMinus 1e-4)
            fit.xUnit!!.symbol shouldBe "M"
            fit.xMin.toString() shouldBe "3.125nM"
            fit.xMax.toString() shouldBe "400nM"
        }
        test("fit4pl over a dilution series gives a quantity EC50") {
            val fit = Fit.fit4pl(concs, elisaStd)
            molar(fit.ec50) shouldBe (4.99812e-8 plusOrMinus 5e-12)
            molar(fit.interp(0.512)) shouldBe (4.40241e-8 plusOrMinus 5e-12)
        }
        test("calibration errors carry the code and readable quantities") {
            val e = shouldThrow<FitRangeException> { Fit.linfit(concs, linStd).interp(1.5) }
            e.code shouldBe FitRangeCode.OUTSIDE_CALIBRATION_RANGE
            e.message shouldContain "(3.125nM to 400nM)"
        }
    }

    context("as a value") {
        test("the literal writes every number exactly and reads back equal") {
            val fit = Fit.fit4pl(serial(400e-9, 8), listOf(0.972, 0.893, 0.745, 0.552, 0.351, 0.211, 0.128, 0.085))
            val text = FitType.format(fit)
            text shouldStartWith ".fit(4pl x=M a="
            text shouldContain " from=3.125nM to=400nM)"
            FitType.parse(text) shouldBe fit
            FitType.parse(text).hashCode() shouldBe fit.hashCode()
            fit.toString() shouldBe text
        }
        test("pairs in any order, numbers or quantities for x values, case of the model ignored") {
            val a = FitType.parse(".fit(4PL x=M a=1.0 b=1.2 c=50nM d=0.1 r2=0.99 n=8 from=1nM to=400nM)")
            val b = FitType.parse(".fit(4pl to=4.0E-7 from=1.0E-9 n=8 r2=0.99 d=0.1 c=5.0E-8 b=1.2 a=1.0 x=M)")
            a shouldBe b
            (a.ec50 as Quantity<*>).toString() shouldBe "50nM"
            FitType.parse(".fit(linear slope=2.0 intercept=1.0 r2=1.0 n=4 from=1.0 to=4.0)").predict(2) shouldBe 5.0
        }
        test("bad literals are refused with the reason") {
            fun bad(text: String, why: String) { shouldThrow<io.kixi.text.ParseException> { FitType.parse(text) }.message shouldContain why }
            bad(".fit(cubic a=1)", "starts with linear or 4pl")
            bad(".fit(linear slope=2 intercept=1 r2=1 n=4 from=1)", "needs to=")
            bad(".fit(linear slope=2 intercept=1 r2=1 n=4 from=1 to=4 q=1)", "Unknown")
            bad(".fit(linear slope=2 slope=3)", "given twice")
            bad(".fit(linear slope=x intercept=1 r2=1 n=4 from=1 to=4)", "not a number")
            bad(".fit(linear slope=NaN intercept=1 r2=1 n=4 from=1 to=4)", "finite")
            bad(".fit(linear slope=2 intercept=1 r2=1 n=4 from=1nM to=4nM)", "x=")
            bad(".fit(linear x=M slope=2 intercept=1 r2=1 n=4 from=1g to=4g)", "not a concentration")
            bad(".fit(linear x=K slope=2 intercept=1 r2=1 n=4 from=1 to=4)", "not a lab base unit")
            bad(".fit(linear slope=2 intercept=1 r2=1 n=4 from=4 to=1)", "smallest")
            bad(".fit(4pl a=1 b=-1 c=1 d=0 r2=1 n=5 from=1 to=4)", "b > 0")
        }
        test("encode and decode; damaged bytes are refused") {
            for (f in FitType.samples) FitType.decode(FitType.encode(f), 1) shouldBe f
            val bytes = FitType.encode(FitType.samples[0])
            shouldThrow<IllegalArgumentException> { FitType.decode(bytes.copyOf(bytes.size - 1), 1) }
            shouldThrow<IllegalArgumentException> { FitType.decode(bytes.copyOf().also { it[0] = 9 }, 1) }
            shouldThrow<IllegalArgumentException> { FitType.decode(bytes, 2) }
        }
        test("equality is exact; -0.0 and 0.0 differ, as Kotlin's Double.equals has it") {
            val a = FitType.parse(".fit(linear slope=2.0 intercept=0.0 r2=1.0 n=4 from=1.0 to=4.0)")
            val b = FitType.parse(".fit(linear slope=2.0 intercept=-0.0 r2=1.0 n=4 from=1.0 to=4.0)")
            a shouldNotBe b
        }
    }
})
