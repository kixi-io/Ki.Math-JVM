// Ki.Math-JVM/settings.gradle.kts

rootProject.name = "Ki.Math-JVM"

// Composite build, as in the other Ki repos: edits to the sibling checkouts are
// live when building from here. Ki.Core is the only dependency of the library;
// Ki.KD, Ki.KS and RevDB are here for the tests that put the bag's values
// through every layer (the corpus round trip). The versions in build.gradle.kts
// are the published fallback for builds without local checkouts.
includeBuild("../Ki.Core-JVM")
includeBuild("../Ki.KD-JVM")
includeBuild("../Ki.KS-JVM")
includeBuild("../RevDB-JVM")
