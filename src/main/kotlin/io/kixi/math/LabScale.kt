package io.kixi.math

import io.kixi.uom.Concentration
import io.kixi.uom.Mass
import io.kixi.uom.MolarMass
import io.kixi.uom.Quantity
import io.kixi.uom.SubstanceAmount
import io.kixi.uom.Volume
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import io.kixi.uom.Unit as KiUnit

/**
 * A dimension the lab functions work in. Values of the dimension are converted to
 * its lab base unit, as doubles, for the arithmetic, and results come back as a
 * quantity in the largest unit of the [ladder] the value reaches 1 in (`52.3nM`,
 * not `5.23E-8M`).
 *
 * A bare number given where the dimension is in play is taken as already in the
 * base unit (M, ℓ, g, mol, Da): the convention LabNexus's lab kit has always had.
 *
 * [code] is stored in a [Fit]'s encoding: never change a code.
 */
internal enum class LabScale(
    val code: Int,
    val what: String,
    val dimension: Class<out KiUnit>,
    val base: KiUnit,
    val ladder: List<KiUnit>,
) {
    CONCENTRATION(1, "concentration", Concentration::class.java, KiUnit.M,
        listOf(KiUnit.M, KiUnit.mM, KiUnit.µM, KiUnit.nM, KiUnit.pM)),
    VOLUME(2, "volume", Volume::class.java, KiUnit.L,
        listOf(KiUnit.L, KiUnit.mL, KiUnit.µL, KiUnit.nL)),
    MASS(3, "mass", Mass::class.java, KiUnit.g,
        listOf(KiUnit.kg, KiUnit.g, KiUnit.mg, KiUnit.µg, KiUnit.ng)),
    AMOUNT(4, "amount", SubstanceAmount::class.java, KiUnit.mol,
        listOf(KiUnit.mol, KiUnit.mmol, KiUnit.µmol, KiUnit.nmol, KiUnit.pmol)),
    MOLAR_MASS(5, "molar mass", MolarMass::class.java, KiUnit.Da, listOf(KiUnit.Da));

    /** [x] in the base unit, as a double (see [toBaseDecimal]). */
    fun toBase(x: Any?): Double = toBaseDecimal(x).toDouble()

    /**
     * [x] in the base unit, exactly: a quantity of this dimension (converted in
     * decimal), a bare number (already in the base unit), or text for either.
     */
    fun toBaseDecimal(x: Any?): BigDecimal {
        val q: Quantity<*> = when (x) {
            is Quantity<*> -> x
            is Number -> return finiteDecimal(x)
            is String -> {
                x.trim().toBigDecimalOrNull()?.let { return it }
                try { Quantity.parse(x.trim()) } catch (e: Exception) {
                    throw MathException("'$x' is not a $what (expected e.g. 5${base.symbol})")
                }
            }
            null -> throw MathException("Missing $what")
            else -> throw MathException("Expected a $what, got ${x::class.simpleName}")
        }
        if (!dimension.isInstance(q.unit)) {
            throw MathException("Expected a $what, got ${q.unit.symbol} (${q.unit.dimensionName})")
        }
        return q.unit.convertValue(finiteDecimal(q.value), base)
    }

    private fun finiteDecimal(n: Number): BigDecimal {
        if ((n is Double && !n.isFinite()) || (n is Float && !n.isFinite())) throw MathException("Expected a finite $what, got $n")
        return decimalOf(n)
    }

    /** The ladder unit [base] reads in: the largest it reaches 1 in, the smallest when none; zero reads in the base unit. */
    fun unitFor(base: Double): KiUnit {
        val magnitude = Math.abs(base)
        if (magnitude == 0.0) return this.base
        return ladder.firstOrNull { magnitude >= perBase(it) * (1 - 1e-9) } ?: ladder.last()
    }

    /** [value] (in the base unit) as a quantity in a readable unit, with no rounding: the double's shortest decimal, shifted. */
    fun fromBase(value: Double): Quantity<KiUnit> {
        if (!value.isFinite()) throw MathException("Result is not finite")
        val unit = unitFor(value)
        return Quantity(inUnit(value, unit), unit)
    }

    /** [value] (in the base unit) as a quantity in a readable unit, to a double's 16 significant digits. */
    fun fromBase(value: BigDecimal): Quantity<KiUnit> {
        val unit = unitFor(value.toDouble())
        val v = value.multiply(base.factor).divide(unit.factor, MathContext.DECIMAL128).round(MathContext.DECIMAL64)
        return Quantity(v.stripTrailingZerosPlain(), unit)
    }

    /** [value] (in the base unit) in [unit], exactly: both are powers of ten apart. */
    fun inUnit(value: Double, unit: KiUnit): BigDecimal =
        BigDecimal(value.toString()).multiply(base.factor).divide(unit.factor, MathContext.DECIMAL128).stripTrailingZerosPlain()

    private fun perBase(unit: KiUnit): Double = unit.factor.divide(base.factor, MathContext.DECIMAL128).toDouble()

    companion object {
        fun of(code: Int): LabScale? = entries.firstOrNull { it.code == code }

        /** The scale for [q]'s dimension, or a [MathException] naming it. */
        fun of(q: Quantity<*>): LabScale = entries.firstOrNull { it.dimension.isInstance(q.unit) } ?: throw MathException(
            "Values in ${q.unit.symbol} (${q.unit.dimensionName}) are not supported here; " +
                    "use concentration, volume, mass, amount, molar mass or plain numbers")

        /** The scale whose base unit is [symbol] (`M`, `ℓ`, `g`, `mol`, `Da`), or null. */
        fun forBaseSymbol(symbol: String): LabScale? {
            val u = KiUnit.getUnit(symbol) ?: return null
            return entries.firstOrNull { it.base == u }
        }
    }
}

/** Any Number a quantity may carry, as a BigDecimal (doubles by their shortest decimal). */
internal fun decimalOf(n: Number): BigDecimal = when (n) {
    is BigDecimal -> n
    is BigInteger -> BigDecimal(n)
    is Double, is Float -> BigDecimal(n.toDouble().toString())
    else -> BigDecimal(n.toLong())
}

/** Trailing zeros dropped, without the exponent form `stripTrailingZeros` gives whole numbers (`1E+1`). */
internal fun BigDecimal.stripTrailingZerosPlain(): BigDecimal {
    val d = stripTrailingZeros()
    return if (d.scale() < 0) d.setScale(0) else d
}
