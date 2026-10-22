package io.kixi.math

import io.kixi.KiException

/**
 * A value Ki.Math cannot work with: a missing or malformed argument, a series too
 * short for the statistic, a fit that does not converge. KS and KQ report the
 * message at the call.
 */
public open class MathException(message: String) : KiException(message)

/**
 * Which range rule an inverse interpolation broke. Being inside the fitted
 * asymptotes is not enough to permit interpolation: a 4PL can compute a
 * concentration far beyond the highest standard, and that is extrapolation even
 * though the reading sits between the asymptotes.
 *
 * - [OUTSIDE_MODEL_RANGE]: the reading is at or beyond a fitted asymptote; the
 *   model cannot invert it at all.
 * - [OUTSIDE_CALIBRATION_RANGE]: the model can invert it, but the inferred x lies
 *   outside the range the standards covered.
 */
public enum class FitRangeCode { OUTSIDE_MODEL_RANGE, OUTSIDE_CALIBRATION_RANGE }

/** A refused [Fit.interp]: the message starts with the [code], so a cell or a log names it. */
public class FitRangeException(public val code: FitRangeCode, detail: String) : MathException("$code: $detail")
