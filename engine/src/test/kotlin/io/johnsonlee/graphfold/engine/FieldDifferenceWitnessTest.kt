package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.FieldSnapshot
import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphfold.engine.source.JavaSourceIndex
import io.johnsonlee.graphfold.engine.source.KotlinSourceIndex
import io.johnsonlee.graphfold.engine.source.ProofPlanner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldDifferenceWitnessTest {
    @TempDir lateinit var directory: Path
    private val field = "App.value:I"
    private val writer = "App.<init>()V"
    private val gap = MethodAnalysisGap("unresolved output", dispatchOnly = true, globalObservation = true)
    private val obligation = ProofObligation(field.substringBefore(':'), SymbolKind.FIELD, "App", emptyList())
    private val declaration = FieldSnapshot(field, "private I", "none", false)

    @Test fun `real Java and Kotlin source field obligations join unique typed bytecode witnesses`() {
        Files.writeString(directory.resolve("App.java"), "public class App { private int value = 1; }")
        val java = JavaSourceIndex(listOf(directory), emptyList()).use { it.index(directory, "App.java", Revision.HEAD) }
        val source = Files.writeString(directory.resolve("App.kt"), "class App { private val value = 1 }")
        val classes = Files.createDirectory(directory.resolve("classes"))
        val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val output = ByteArrayOutputStream()
        val compiled = PrintStream(output).use { stream -> K2JVMCompiler().exec(stream,
            "-no-stdlib", "-no-reflect", "-classpath", library.toString(), "-d", classes.toString(), source.toString()) }
        assertEquals(ExitCode.OK, compiled, output.toString())
        val kotlin = KotlinSourceIndex(classes).use { it.index(directory, "App.kt", Revision.HEAD) }
        val base = snapshot()
        val comparison = SnapshotComparison(base, addField(base))
        for (indexed in listOf(java, kotlin)) {
            assertTrue(indexed.diagnostics.isEmpty(), indexed.diagnostics.toString())
            val symbol = indexed.symbols.single { it.kind == SymbolKind.FIELD }
            val planned = ProofPlanner.plan(indexed, listOf(symbol.location)).single()
            assertEquals("App.value", planned.identity)
            assertTrue(comparison.analysisGaps(planned).isEmpty())
            assertTrue(writer in comparison.differences(planned))
        }
    }

    @Test fun `added and removed field operations in an independently reached constructor are surviving witnesses`() {
        val base = snapshot()
        val head = addField(base)
        for ((before, after) in listOf(base to head, head to base)) {
            val comparison = SnapshotComparison(before, after)
            assertTrue(comparison.analysisGaps(obligation).isEmpty())
            assertTrue(writer in comparison.differences(obligation))
            assertEquals(listOf(gap.message), comparison.analysisGaps(obligation, resourceProof = true))
        }
    }

    @Test fun `changed reference counts can witness a new declaration shadowing an inherited field`() {
        // JVM field references may name the receiver class before the field is declared on that class.
        val inherited = declaration.copy(identity = "Parent.value:I")
        val base = snapshot().let { it.copy(fields = mapOf(inherited.identity to inherited),
            declarations = it.declarations + ("Parent" to "public class"),
            methods = it.methods.mapValues { (_, method) -> method.copy(references = listOf(field)) }) }
        val head = addField(base, listOf(field, field))
        assertTrue(SnapshotComparison(base, head).analysisGaps(obligation).isEmpty())
    }

    @Test fun `an unrelated method change cannot lend its difference to an unread added field`() {
        val base = snapshot()
        val head = addField(base, emptyList())
        assertEquals(listOf(gap.message), SnapshotComparison(base, head).analysisGaps(obligation))
    }

    @Test fun `unchanged field declaration cannot borrow a new reference in another changed method`() {
        val base = snapshot().copy(fields = mapOf(field to declaration))
        val unrelated = "App.unseen()V"
        val method = base.methods.getValue(writer).copy(identity = unrelated, modifiers = setOf(MethodModifier.PRIVATE))
        val before = base.copy(methods = base.methods + (unrelated to method))
        val head = addField(before).let { it.copy(methods = it.methods +
            (unrelated to method.copy(canonical = "unseen change", referenceCanonical = "unseen change"))) }
        assertEquals(listOf(gap.message), SnapshotComparison(before, head).analysisGaps(obligation))
    }

    @Test fun `added or removed directly reached writer can establish its own field operation`() {
        val helper = "App.write()V"
        val base = snapshot().copy(methodDiagnostics = emptyMap())
        val constructor = base.methods.getValue(writer).copy(canonical = "call", referenceCanonical = "call", calls = setOf(helper))
        val added = MethodSnapshot(helper, "write", emptySet(), setOf(MethodModifier.PRIVATE), emptySet(), references = listOf(field))
        val head = base.copy(fields = mapOf(field to declaration), methods = mapOf(writer to constructor, helper to added),
            methodDiagnostics = mapOf(helper to listOf(gap)))
        for ((before, after) in listOf(base to head, head to base)) {
            val comparison = SnapshotComparison(before, after)
            assertTrue(comparison.analysisGaps(obligation).isEmpty())
            assertTrue(helper in comparison.differences(obligation))
        }
    }

    @Test fun `a field writer reached only through unresolved dispatch has no independent witness`() {
        val base = snapshot()
        val callback = "Hidden.write()V"
        val hidden = base.methods.getValue(writer).copy(identity = callback, modifiers = setOf(MethodModifier.PRIVATE))
        val entry = base.methods.getValue(writer).copy(calls = setOf(callback))
        val before = base.copy(methods = mapOf(writer to entry, callback to hidden),
            declarations = base.declarations + ("Hidden" to "private class"))
        val after = before.copy(fields = mapOf(field to declaration), methods = before.methods +
            (callback to hidden.copy(canonical = "write", referenceCanonical = "write", references = listOf(field))))
        assertEquals(listOf(gap.message), SnapshotComparison(before, after).analysisGaps(obligation))
    }

    @Test fun `base-only reachability cannot witness a newly declared head field or the reverse removal`() {
        val helper = "Hidden.write()V"
        val original = snapshot()
        val entry = original.methods.getValue(writer).copy(calls = setOf(helper))
        val body = MethodSnapshot(helper, "before", emptySet(), setOf(MethodModifier.PRIVATE), emptySet())
        val base = original.copy(methods = mapOf(writer to entry, helper to body),
            declarations = original.declarations + ("Hidden" to "private class"),
            methodDiagnostics = mapOf(helper to listOf(gap)))
        val head = base.copy(fields = mapOf(field to declaration), methods = mapOf(
            writer to entry.copy(canonical = "removed call", referenceCanonical = "removed call", calls = emptySet()),
            helper to body.copy(canonical = "write", referenceCanonical = "write", references = listOf(field))))
        for ((before, after) in listOf(base to head, head to base)) {
            assertEquals(listOf(gap.message), SnapshotComparison(before, after).analysisGaps(obligation))
        }
    }

    @Test fun `unchanged symbolic reference counts do not attribute an unrelated body change to a shadow declaration`() {
        val inherited = declaration.copy(identity = "Parent.value:I")
        val original = snapshot()
        val base = original.copy(fields = mapOf(inherited.identity to inherited),
            declarations = original.declarations + ("Parent" to "public class"),
            methods = original.methods.mapValues { (_, method) -> method.copy(references = listOf(field)) })
        val head = addField(base, listOf(field))
        for ((before, after) in listOf(base to head, head to base)) {
            assertEquals(listOf(gap.message), SnapshotComparison(before, after).analysisGaps(obligation))
        }
    }

    @Test fun `removing an inherited reference cannot witness an unread shadow field addition or reverse removal`() {
        val inherited = declaration.copy(identity = "Parent.value:I")
        val original = snapshot()
        val base = original.copy(fields = mapOf(inherited.identity to inherited),
            declarations = original.declarations + ("Parent" to "public class"),
            methods = original.methods.mapValues { (_, method) -> method.copy(references = listOf(field)) })
        val head = addField(base, emptyList())
        for ((before, after) in listOf(base to head, head to base)) {
            assertEquals(listOf(gap.message), SnapshotComparison(before, after).analysisGaps(obligation))
        }
    }

    @Test fun `source field names must resolve to exactly one JVM descriptor across both revisions`() {
        val base = snapshot()
        val head = addField(base)
        val alternative = declaration.copy(identity = "App.value:J")
        val ambiguous = head.copy(fields = head.fields + (alternative.identity to alternative))
        assertEquals(listOf(gap.message), SnapshotComparison(base, ambiguous).analysisGaps(obligation))
        val previousType = base.copy(fields = mapOf(alternative.identity to alternative))
        assertEquals(listOf(gap.message), SnapshotComparison(previousType, head).analysisGaps(obligation))
        val neighbor = declaration.copy(identity = "App.valueExtra:I")
        val unrelated = head.copy(fields = mapOf(neighbor.identity to neighbor), methods = head.methods.mapValues {
            (_, method) -> method.copy(references = listOf(neighbor.identity))
        })
        assertEquals(listOf(gap.message), SnapshotComparison(base, unrelated).analysisGaps(obligation))
    }

    @Test fun `field witnesses preserve body gaps and unverified compiler provenance`() {
        val bodyGap = MethodAnalysisGap("missing body")
        val base = snapshot().copy(methodDiagnostics = mapOf(writer to listOf(gap, bodyGap)))
        val head = addField(base)
        assertEquals(listOf(bodyGap.message), SnapshotComparison(base, head).analysisGaps(obligation))
        val gaps = SnapshotComparison(base, head, originsVerified = false).analysisGaps(obligation)
        assertTrue(gap.message in gaps)
        assertTrue(bodyGap.message in gaps)
    }

    private fun snapshot(): ProgramSnapshot {
        val method = MethodSnapshot(writer, "before", emptySet(), setOf(MethodModifier.PUBLIC), emptySet())
        return ProgramSnapshot(mapOf(writer to method), mapOf("App" to "public class"), emptyList(), emptyList(),
            entryClasses = setOf("App"), methodDiagnostics = mapOf(writer to listOf(gap)))
    }

    private fun addField(base: ProgramSnapshot, references: List<String> = listOf(field)): ProgramSnapshot {
        val method = base.methods.getValue(writer).copy(canonical = "after", referenceCanonical = "after", references = references)
        return base.copy(methods = base.methods + (writer to method), fields = base.fields + (field to declaration))
    }
}
