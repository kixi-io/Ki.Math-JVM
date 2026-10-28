package io.kixi.math

import io.kixi.kd.KD
import io.kixi.revdb.RevDB
import io.kixi.types.TypeBags
import io.kixi.uom.Quantity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** The `math` bag as KD, KS, KQ and RevDB see it. */
class MathBagTest : FunSpec({

    val bag = TypeBags.byName("math")!!

    test("the bag is discovered from the classpath, with no problems") {
        bag.shouldBeInstanceOf<MathBag>()
        TypeBags.problems.filter { "math" in it }.shouldBeEmpty()
        TypeBags.literal("fit") shouldBe FitType
        TypeBags.typeNamed("Fit") shouldBe FitType
        TypeBags.function("linfit")!!.arity shouldBe 2..2
    }

    test("every sample passes the contract's self-check and the corpus round trip") {
        corpusProblems(bag).shouldBeEmpty()
    }

    test("KD reads a fit in a document") {
        val t = KD.read("curve fit=.fit(linear slope=2.0 intercept=1.0 r2=1.0 n=4 from=1.0 to=4.0)")
        t["fit"].shouldBeInstanceOf<Fit>().predict(3) shouldBe 7.0
    }

    test("KS: use ki.math, literals, members and functions") {
        ks("use ki.math\nsay math.mean([1, 2, 3])") shouldBe "2"
        ks("use ki.math.*\nlet f = linfit([1, 2, 3, 4], [3.0, 5.0, 7.0, 9.0])\nsay f.slope\nsay f.predict(10)\nsay interp(f, 7.0)") shouldBe "2\n21\n3"
        ks("use ki.math.*\nsay mean([1mM, 3mM])") shouldBe "2mM"
        ks("let f = .fit(linear slope=2.0 intercept=1.0 r2=1.0 n=4 from=1.0 to=4.0)\nsay f is Fit\nsay f.kind") shouldBe "true\nlinear"
        ks("use ki.math.round\nsay round(2.345, 2)") shouldBe "2.35"
        val e = runCatching { ks("use ki.math.*\nsay sd([1])") }.exceptionOrNull()!!
        e.message!! shouldContain "sd needs at least 2 values"
    }

    test("KQ: bag functions in queries, KQ's own mean first, fits stored and read") {
        RevDB.inMemory().use { db ->
            db.writeBlocking {
                createCollection("rev:collection curves")
                insert("curves", "c name=\"std\" xs=[1 2 3 4] ys=[3.0 5.0 7.0 9.0]")
            }
            db.kq("from c in curves select linfit(c.xs, c.ys).slope").single().values[0] shouldBe 2.0
            db.kq("from c in curves select round(mean(c.ys), 1)").single().values[0] shouldBe 6.0
            db.kq("from c in curves select median(c.xs)").single().values[0] shouldBe 2.5
            // a measure from a bag function, stored and read back as one
            val ms = db.kq("from c in curves select meanSd(c.ys)").single().values[0]
            ms shouldBe Stats.meanSd(listOf(3.0, 5.0, 7.0, 9.0))
            db.writeBlocking { insert("curves", io.kixi.kd.Tag("c").apply { setAttribute("name", value = "ms"); setAttribute("m", value = ms) }) }
            db.kq("from c in curves where c.name == \"ms\" select c.m.uncertainty").single().values[0] shouldBe (ms as io.kixi.Measure).uncertainty
            val fit = Fit.linfit(listOf(1, 2, 3, 4), listOf(3.0, 5.0, 7.0, 9.0))
            db.writeBlocking { insert("curves", io.kixi.kd.Tag("c").apply { setAttribute("name", value = "stored"); setAttribute("fit", value = fit) }) }
            db.kq("from c in curves where c.name == \"stored\" select c.fit.predict(2), c.fit.interp(9.0)").single().values shouldBe listOf(5.0, 4.0)
            db.kq("from c in curves where c.name == \"stored\" select c.fit").single().values[0] shouldBe fit
        }
    }

    test("an x quantity converts through the fit's dimension in KS") {
        val fit = Fit.linfit(listOf(Quantity.parse("1µM"), Quantity.parse("2µM")), listOf(1.0, 2.0))
        ks("say f.predict(1500nM)", mapOf("f" to fit)) shouldBe "1.5"
        ks("say f.interp(1.5)", mapOf("f" to fit)) shouldBe "1.5µM"
    }
})
