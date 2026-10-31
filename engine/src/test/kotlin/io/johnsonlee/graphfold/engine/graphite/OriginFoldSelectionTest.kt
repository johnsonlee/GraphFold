package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.origin.OriginCatalog
import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphfold.engine.origin.normalizeOriginClasses
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldSites
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginFoldSelectionTest {
    @TempDir lateinit var temporary: Path

    @Test fun `original anonymous caller Cypher selects only its original scope after remapping`() {
        val project = project()
        val rule = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite) WHERE cs.caller_class = 'Host\$1' AND cs.callee_name = 'enabled' RETURN cs",
            "args" to mapOf(0 to "checkout"), "value" to false,
        ))
        val audits = mutableMapOf<Int, RuleAuditUpdate>()
        assertSelection(GraphiteAnalyzer { index, update -> audits[index] = update }.analyzeOriginProject(project, listOf(rule)))
        assertEquals(2, audits.getValue(0).selectedNodes.size)
        assertEquals(1, audits.getValue(0).matchedCallCount)
        assertTrue(audits.getValue(0).selectedNodes.all { it.caller.signature == "Host\$1.run()" })
        assertTrue(audits.getValue(0).selectedNodes.none { it.caller.signature.contains("left") })
    }

    @Test fun `constant patterns match original owners and retain argument constraints`() {
        val project = project()
        val rule = ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf("caller_class" to "Host\$1", "callee_name" to "enabled")),
            "args" to mapOf(0 to "checkout"), "value" to false,
        ))
        val audits = mutableMapOf<Int, RuleAuditUpdate>()
        assertSelection(GraphiteAnalyzer { index, update -> audits[index] = update }.analyzeOriginProject(project, listOf(rule)))
        assertEquals(2, audits.getValue(0).selectedNodes.size)
        assertEquals(1, audits.getValue(0).matchedCallCount)
        assertTrue(audits.getValue(0).selectedNodes.all { it.caller.signature == "Host\$1.run()" })
        assertTrue(audits.getValue(0).selectedNodes.none { it.caller.signature.contains("left") })
    }

    @Test fun `typed selection remaps method parameter return arrays and erased result without changing constants`() {
        val original = CallSiteKey("a.Host\$1.run(a.Host\$1[][])", "([[La/Host\$1;)La/Host\$1;",
            "a.Host\$1.get()", "()La/Host\$1;", 3)
        val parsed = FoldSites.parse(mapOf("select" to "MATCH (cs:CallSite) RETURN cs", "value" to false,
            "args" to mapOf(0 to "a.Host\$1"), "receiver_args" to mapOf(0 to "a.Host\$1")))
        val rule = parsed.copy(selected = setOf(original), resultTypes = mapOf(original to "a.Host\$1[]"))
        val mapped = remapOriginSelection(rule, mapOf("a.Host\$1" to "a.Host\$stable"))
        val key = mapped.selected!!.single()
        assertEquals("a.Host\$stable.run(a.Host\$stable[][])", key.caller)
        assertEquals("([[La/Host\$stable;)La/Host\$stable;", key.callerDescriptor)
        assertEquals("a.Host\$stable.get()", key.callee)
        assertEquals("()La/Host\$stable;", key.calleeDescriptor)
        assertEquals(3, key.ordinal)
        assertEquals(mapOf(key to "a.Host\$stable[]"), mapped.resultTypes)
        assertEquals(rule.arguments, mapped.arguments)
        assertEquals(rule.receiverArguments, mapped.receiverArguments)
        assertEquals(rule.value, mapped.value)
        assertEquals(rule.select, mapped.select)
    }

    @Test fun `empty rules analyze normalized code and stale caller keys never replace original query`() {
        val project = project()
        val analyzer = GraphiteAnalyzer()
        val plain = analyzer.analyzeOriginProject(project, emptyList())
        assertTrue("Host.work()V" in plain.methods.getValue("Host\$left.run()V").calls)
        val selected = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite) WHERE cs.caller_class = 'Host\$1' AND cs.callee_name = 'enabled' RETURN cs",
            "args" to mapOf(0 to "checkout"), "value" to false,
        )).copy(selected = emptySet())
        assertSelection(analyzer.analyzeOriginProject(project, listOf(selected)))
    }

    @Test fun `projects without origin renames retain ordinary analysis semantics`() {
        val project = project().copy(owners = emptyMap())
        val analyzer = GraphiteAnalyzer()
        assertEquals(analyzer.analyze(project.compiled.classes, emptyList()),
            analyzer.analyzeOriginProject(project, emptyList()))
    }

    @Test fun `selection remapping preserves external types and rejects malformed or unresolved keys`() {
        val parsed = FoldSites.parse(mapOf("select" to "MATCH (cs:CallSite) RETURN cs", "value" to false))
        assertFailsWith<IllegalArgumentException> { remapOriginSelection(parsed, emptyMap()) }
        val key = CallSiteKey("sample.Host.run(java.lang.String,int[])", "(Ljava/lang/String;[I)V",
            "library.Flags.enabled(java.lang.String)", "(Ljava/lang/String;)Z", 0)
        val rule = parsed.copy(selected = setOf(key), resultTypes = mapOf(key to "java.lang.Boolean"))
        assertEquals(rule, remapOriginSelection(rule, mapOf("sample.Unrelated" to "sample.Other")))
        listOf("missing-parentheses", "sample.Host.run(").forEach { malformed ->
            assertFailsWith<IllegalArgumentException> {
                remapOriginSelection(rule.copy(selected = setOf(key.copy(caller = malformed))), emptyMap())
            }
        }
    }

    private fun assertSelection(snapshot: ProgramSnapshot) {
        val left = snapshot.methods.getValue("Host\$left.run()V").calls
        assertFalse("Host.work()V" in left)
        assertTrue("Host.tail()V" in left, "other experiment argument must remain")
        assertTrue("Host.work()V" in snapshot.methods.getValue("Host\$right.run()V").calls,
            "query must not broaden to the second anonymous class")
        assertEquals(listOf(1), snapshot.matchedRules)
        assertTrue(snapshot.diagnostics.any { it.contains("other") })
    }

    private fun project(): OriginProject {
        val source = temporary.resolve("Host.java")
        Files.writeString(source, """
            public class Host {
                static boolean enabled(String id) { return true; }
                static void work() {}
                static void tail() {}
                public Runnable first() { return new Runnable() { public void run() {
                    if (enabled("checkout")) work();
                    if (enabled("other")) tail();
                } }; }
                public Runnable second() { return new Runnable() { public void run() {
                    if (enabled("checkout")) work();
                } }; }
            }
        """.trimIndent())
        val classes = Files.createDirectories(temporary.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val normalized = normalizeOriginClasses(classes, temporary.resolve("normalized"),
            mapOf("Host\$1" to "Host\$left", "Host\$2" to "Host\$right"))
        assertTrue(normalized.diagnostics.isEmpty(), normalized.diagnostics.toString())
        return OriginProject(CompiledProject(normalized.classes, listOf(temporary), emptyList()),
            classes, OriginCatalog(emptyList(), emptyList()), normalized.owners)
    }
}
