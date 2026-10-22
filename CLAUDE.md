# Ki.Math-JVM

The `math` type bag (Ki type bags design, sections 2 and 5.1; the design is in Ki.Docs and the RevDB project docs). Kotlin 2.3.0 with `explicitApi()`, Kotest 6.0.7, JDK 21.

## Composite build
Includes `../Ki.Core-JVM` (the only main dependency) and `../Ki.KD-JVM`, `../Ki.KS-JVM`, `../RevDB-JVM` (tests only). Ask before adding a dependency.

## Rules
- The bag is `MathBag` (a class with a public no-argument constructor, listed in `META-INF/services/io.kixi.types.TypeBag`); nothing registers it. Tests reach it with `TypeBags.byName("math")`.
- A type's stored encoding and sort key never change for a version that was released: bump `MathBag.VERSION` and keep decoding every older version. `LabScale.code` and `Fit.Model.code` are stored.
- `Fit`'s literal writes every number exactly (a double's shortest decimal, shifted to a readable unit for x values); `parse(format(f)) == f` must hold. Keep `FitType.samples` covering both models, every lab dimension and the far-outside-the-ladder case.
- Statistics are decimal (`Stats.series` holds BigDecimals), so exact inputs give exact answers; nothing is rounded for display. `zprime` matches KQ's built-in value for value.
- Function and constant names are global across bags (and KQ's built-ins win in KQ): check the other bags before adding one.
- `BagCorpus.kt` (the corpus round trip) is shared with Ki.Sci-JVM's tests; keep the copies alike.

## Working agreements
- Commit straight to `master`; never create a branch unless Dan asks. Code and docs in separate commits, author `dleuck <dan@kixi.io>`. Don't publish or change the version without asking.
