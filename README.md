# Ki.Math-JVM

The `math` type bag: lab fits and statistics for KD, KS, KQ and RevDB. A type bag
is a library that adds value types, functions and constants to every Ki layer at
once (the Ki type bags design, in Ki.Docs). Ki.Math depends on Ki.Core alone.

```kotlin
// Kotlin
val concs = listOf("400nM", "200nM", "100nM", "50nM", "25nM", "12.5nM", "6.25nM", "3.125nM").map { Quantity.parse(it) }
val fit = Fit.fit4pl(concs, listOf(0.972, 0.893, 0.745, 0.552, 0.351, 0.211, 0.128, 0.085))   // or Fit.linfit
fit.ec50                      // 49.98nM
fit.interp(0.512)             // 44.02nM, refused outside the standards' range
Stats.sd(listOf(0.412, 0.398, 0.405))   // 0.007
```

```ks
// KS
use ki.math.*
let curve = fit4pl([400nM, 200nM, 100nM, 50nM, 25nM, 12.5nM, 6.25nM, 3.125nM],
                   [0.972, 0.893, 0.745, 0.552, 0.351, 0.211, 0.128, 0.085])
say curve.ec50
say interp(curve, 0.512)
say mean([1mM, 3mM])          // 2mM
```

```kq
// KQ: the functions need no import; KQ's own mean and zprime come first
from p in plates select p.barcode, linfit(p.std, p.abs).r2
```

## What is in it

- **`Fit`** (`.fit(…)`): a standard curve, a least-squares line or a
  four-parameter logistic (Nelder–Mead), with `interp` (reading to x, strictly:
  `OUTSIDE_MODEL_RANGE` or `OUTSIDE_CALIBRATION_RANGE`, never a clamped value) and
  `predict` (x to reading). When the standards were quantities the fit remembers
  the dimension and answers in a readable unit. A fit is a value: it is written
  `.fit(4pl x=M a=… b=… c=52.3nM d=… r2=… n=8 from=1.5625nM to=400nM)` with every
  number exact, stored by RevDB and compared by its parameters.
- **Statistics** (`Stats`): `mean`, `sd`, `cv`, `sem`, `median`, `quantile`
  (type 7), `blankSubtract`, `zprime` (as KQ's) and `round` (half away from zero,
  as Excel's ROUND). Sample deviations (n − 1). Decimal arithmetic, so exact
  inputs give exact answers; nothing is rounded for display.
- **Units**: series of concentrations, volumes, masses, amounts and molar masses
  are converted to the lab base unit (M, ℓ, g, mol, Da); a bare number among them
  is taken as already in that unit.

`Measure` (a value with uncertainty) and `Vector` are not here: they go into
Ki.Core, next to Grid, in their own milestone.

## Build

A composite build with the sibling checkouts `../Ki.Core-JVM`, `../Ki.KD-JVM`,
`../Ki.KS-JVM` and `../RevDB-JVM` (the last three for the tests only). Kotlin
2.3.0, Kotest 6.0.7, JDK 21.

```
./gradlew test
```

The tests include the corpus round trip: every sample value of the bag goes
through KD text, KS literals and members, and RevDB storage, indexes and KQ.

MIT license.
