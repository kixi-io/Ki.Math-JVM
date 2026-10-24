package io.kixi.math

import io.kixi.kd.KD
import io.kixi.kd.Tag
import io.kixi.ks.KSRuntime
import io.kixi.ks.interp.Interpreter
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kixi.revdb.Bound
import io.kixi.revdb.RevDB
import io.kixi.types.TypeBag
import io.kixi.types.TypeBags
import io.kixi.types.ValueType
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.Arrays

/*
 * The corpus round trip (Ki type bags design, 7, TB2): every sample value of a
 * bag's types goes through every layer that stores or reads it, and comes back
 * equal. A copy of this file is in each bag's tests (Ki.Math, Ki.Sci, Ki.Bio); keep them
 * alike.
 */

/** Runs KS [source] and returns what it said, trimmed; [bind] defines names first. */
internal fun ks(source: String, bind: Map<String, Any?> = emptyMap()): String {
    val out = StringWriter()
    val i = Interpreter(KSRuntime(hostLang = false, colorOutput = false,
        outputWriter = PrintWriter(out, true), errorWriter = PrintWriter(StringWriter(), true)))
    for ((k, v) in bind) i.define(k, v)
    i.executeProgram(Parser(Lexer(source).tokenize()).parse())
    return out.toString().trim().replace("\r\n", "\n")
}

/** What went wrong putting [bag]'s samples through KD, KS and RevDB, or nothing. */
@Suppress("UNCHECKED_CAST")
internal fun corpusProblems(bag: TypeBag): List<String> {
    val out = ArrayList<String>()
    out += TypeBags.check(bag)
    for (t0 in bag.types) {
        val t = t0 as ValueType<Any>
        if (t.literals.isEmpty()) continue
        val samples = t.samples
        fun fail(what: String, v: Any?) { out.add("${bag.name}.${t.name}: $what for ${TypeBags.format(v!!) ?: v}") }

        // KD: as a value, an attribute, in a list and as a map key, through the text and back.
        for (v in samples) {
            val tag = Tag("t").apply {
                values.add(v)
                setAttribute("a", value = v)
                setAttribute("l", value = listOf(v, "x", v))
                setAttribute("m", value = linkedMapOf(v to 1))
            }
            val text = tag.toString()
            val back = try { KD.read(text) } catch (e: Exception) { fail("KD could not read $text: $e", v); continue }
            if (back != tag) fail("KD text $text read back as $back", v)
        }

        // KS: the literal reads to an equal value of the type, and every property agrees.
        for (v in samples) {
            val lit = t.format(v)
            val props = t.members.filter { it.isProperty }.joinToString("") { "\nsay x.${it.name} == expected.${it.name}" }
            val said = try { ks("let x = $lit\nsay x == expected\nsay x is ${t.name}$props", mapOf("expected" to v)) }
            catch (e: Exception) { fail("KS failed on $lit: $e", v); continue }
            val lines = said.lines()
            if (lines.any { it != "true" }) fail("KS said $lines for $lit", v)
        }

        // RevDB: stored, reopened, read back; indexed when the type orders, in sort-key order.
        val file = Files.createTempDirectory("corpus").resolve("store.mv.db")
        try {
            RevDB.open(file).use { db ->
                db.writeBlocking {
                    createCollection(if (t.ordered) "rev:collection samples { index v }" else "rev:collection samples")
                    for ((i, v) in samples.withIndex()) insert("samples", Tag("s").apply { setAttribute("i", value = i); setAttribute("v", value = v) })
                }
            }
            RevDB.open(file).use { db ->
                db.readBlocking {
                    val byI = nodes("samples").associate { (it.tag["i"] as Number).toInt() to it.tag["v"] }
                    for ((i, v) in samples.withIndex()) if (byI[i] != v) fail("RevDB read back ${byI[i]}", v)
                    if (t.ordered) {
                        val expected = samples.indices.sortedWith { a, b ->
                            Arrays.compareUnsigned(t.sortKey(samples[a]), t.sortKey(samples[b])).let { c -> if (c != 0) c else a.compareTo(b) }
                        }.map { samples[it] }
                        val ranged = range("samples", "v", Bound.inclusive(expected.first()), Bound.inclusive(expected.last()))
                            .map { it.tag["v"] }.toList()
                        if (ranged != expected) out.add("${bag.name}.${t.name}: the index orders $ranged, the sort keys $expected")
                        for (v in samples) {
                            val n = lookup("samples", "v", v).count()
                            if (n != samples.count { it == v }) fail("index lookup found $n", v)
                        }
                        val kq = kq("from s in samples orderby s.v select s.v").map { it.values[0] }.toList()
                        if (kq != expected) out.add("${bag.name}.${t.name}: KQ orders $kq, the sort keys $expected")
                    }
                    for (v in samples) {
                        val hits = kq("from s in samples where s.v == \$p select s.i", mapOf("p" to v)).map { (it.values[0] as Number).toInt() }.toList()
                        val want = samples.indices.filter { samples[it] == v }
                        if (hits.sorted() != want) fail("KQ equality found $hits, not $want", v)
                    }
                }
            }
        } catch (e: Exception) {
            out.add("${bag.name}.${t.name}: RevDB failed: $e")
        }
    }
    return out
}
