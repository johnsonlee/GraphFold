package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap

import io.johnsonlee.graphfold.engine.origin.OriginCatalog
import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphfold.engine.inputs.InlineCopyEvidence
import io.johnsonlee.graphfold.engine.inputs.InlineDeclarationEvidence
import io.johnsonlee.graphfold.engine.inputs.InlineEvidence
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class GraphiteAnalyzerBoundaryTest {
    @TempDir lateinit var directory: Path

    @Test fun `origin analysis retains missing class references rather than trusting normalized owner evidence`() {
        val classes = compile("missing", "return \"ok\";", "public Missing dependency; } class Missing {")
        Files.delete(classes.resolve("Missing.class"))
        val snapshot = GraphiteAnalyzer().analyzeOriginProject(project(classes), emptyList())
        assertTrue(snapshot.diagnostics.any { it == "Unresolved class reference: Missing" }, snapshot.diagnostics.toString())
    }

    @Test fun `general selection refuses a graph whose native body cannot support the query`() {
        val classes = compile("native", "return nativeValue();", "public static native String nativeValue();")
        val rule = io.johnsonlee.graphite.input.FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite) RETURN cs LIMIT 1", "value" to false))
        val failure = assertFailsWith<IllegalArgumentException> { GraphiteAnalyzer().analyze(classes, listOf(rule)) }
        assertTrue("NATIVE" in failure.message.orEmpty(), failure.message)
    }

    @Test fun `application archives must be prepared by the adapter before analysis`() {
        val archive = Files.write(directory.resolve("application.jar"), byteArrayOf())
        val analyzer = GraphiteAnalyzer()
        val failure = assertFailsWith<IllegalArgumentException> { analyzer.analyze(archive, emptyList()) }
        assertTrue("prepared class directory" in failure.message.orEmpty())
        val classes = compile("classes")
        val project = project(classes)
        assertFailsWith<IllegalArgumentException> {
            analyzer.analyzeOriginProject(project.copy(compiled = project.compiled.copy(classes = archive)), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            analyzer.analyzeOriginProject(project.copy(sourceClasses = archive), emptyList())
        }
    }

    @Test fun `prepared directory names do not carry archive format policy`() {
        val classes = compile("classes.jar")
        val snapshot = GraphiteAnalyzer().analyze(classes, emptyList())
        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertTrue("Sample.run()Ljava/lang/String;" in snapshot.methods)
    }

    @Test fun `origin analysis supports resource proofs with and without exported production metadata`() {
        val classes = compile("classes")
        val base = project(classes)
        val variants = listOf(base, base.copy(compiled = base.compiled.copy(
            production = ContentInventory(emptyMap(), emptyMap()))))
        variants.forEach { project ->
            val snapshot = GraphiteAnalyzer().analyzeOriginProject(project, emptyList(), dependencyResources = true)
            assertTrue(snapshot.resourceDependenciesAnalyzed, snapshot.diagnostics.toString())
            assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
            assertEquals(mapOf("Sample" to "Sample"), snapshot.originalOwners)
        }
    }

    @Test fun `origin annotations merge with existing reflection gaps rather than replacing them`() {
        val classes = compile("reflection", "Sample.class.getDeclaredMethods(); return \"ok\";")
        val identity = "Sample.run()Ljava/lang/String;"
        val project = project(classes).copy(methodDiagnostics = mapOf(identity to listOf(MethodAnalysisGap("origin evidence"))))
        val snapshot = GraphiteAnalyzer().analyzeOriginProject(project, emptyList())
        val gaps = snapshot.methodDiagnostics.getValue(identity)
        assertTrue(gaps.any { it.message == "origin evidence" })
        assertTrue(gaps.any { it.message != "origin evidence" }, gaps.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `embedded resource jars never enter graph inputs with or without dependency staging`(withDependency: Boolean) {
        val classes = compile("embedded")
        val dependency = directory.resolve("dependency.jar")
        JarOutputStream(Files.newOutputStream(dependency)).use { }
        val dependencies = if (withDependency) listOf(dependency) else emptyList()
        val baseline = GraphiteAnalyzer().analyze(classes, emptyList(), dependencies)
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ResourceOnly", null, "java/lang/Object", null)
            visitEnd()
        }.toByteArray()
        val embedded = classes.resolve("resource.jar")
        JarOutputStream(Files.newOutputStream(embedded)).use { jar ->
            jar.putNextEntry(JarEntry("ResourceOnly.class")); jar.write(bytes); jar.closeEntry()
        }
        val snapshot = GraphiteAnalyzer().analyze(classes, emptyList(), dependencies)
        assertEquals(baseline.declarations, snapshot.declarations)
        assertEquals(baseline.methods, snapshot.methods)
        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertTrue(Files.isRegularFile(embedded), "Original resource evidence remains available")
        assertEquals(bytes.toList(), java.util.jar.JarFile(embedded.toFile()).use { jar ->
            jar.getInputStream(jar.getJarEntry("ResourceOnly.class")).use { it.readBytes().toList() }
        })
    }

    @Test fun `method origin gaps survive without an owner normalization map`() {
        val classes = compile("origin-gap")
        val identity = "Sample.run()Ljava/lang/String;"
        val gap = MethodAnalysisGap("Missing source identity for generated method")
        val input = project(classes).copy(owners = emptyMap(), methodDiagnostics = mapOf(identity to listOf(gap)))
        val result = GraphiteAnalyzer().analyzeOriginProject(input, emptyList())
        assertTrue(gap in result.methodDiagnostics.getValue(identity))
        assertTrue(identity in result.methods, "Missing attribution must retain the original bytecode")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `inline-only origin evidence requires a valid catalog before authorizing declaration removal`(invalid: Boolean) {
        val classes = compile("inline-$invalid", "if (enabled()) return \"copied\"; return null;",
            "private static String helper() { return \"copied\"; } public static boolean enabled() { return true; }")
        val bytes = Files.readAllBytes(classes.resolve("Sample.class"))
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        val caller = "Sample.run()Ljava/lang/String;"
        val target = "Sample.helper()Ljava/lang/String;"
        val instructions = node.methods.single { it.name == "run" }.instructions.filter { it.opcode >= 0 }
        val start = instructions.indexOfFirst { it is LdcInsnNode && it.cst == "copied" }
        assertTrue(start >= 0)
        val evidence = InlineEvidence(listOf(InlineDeclarationEvidence(target, directory.resolve("Sample.kt"), "source", 0, 1, true)),
            listOf(InlineCopyEvidence(target, caller, start, start + 1, "class")), mapOf("Sample" to "class"))
        val catalog = OriginCatalog(emptyList(), if (invalid) listOf("Invalid claimed compiler provenance") else emptyList(),
            inlineEvidence = listOf(evidence))
        val input = project(classes).copy(owners = emptyMap(), catalog = catalog)
        val rule = io.johnsonlee.graphite.input.ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
            "callee_class" to "Sample", "callee_name" to "enabled")), "value" to false))
        val result = GraphiteAnalyzer().analyzeOriginProject(input, listOf(rule))
        assertEquals(listOf(1), result.matchedRules)
        assertEquals(!invalid, target in result.controlRemovedMethods, result.toString())
        assertTrue(target in result.methods, "Proof evidence must not delete the original declaration")
        val withoutEvidence = input.copy(catalog = OriginCatalog(emptyList(), emptyList()))
        val raw = GraphiteAnalyzer().analyzeOriginProject(withoutEvidence, listOf(rule))
        assertTrue(target !in raw.controlRemovedMethods, "Folding another body cannot excuse an uncalled declaration without evidence")
    }

    private fun project(classes: Path) = OriginProject(StaticScope(classes, emptyList(), emptyList()), classes,
        OriginCatalog(emptyList(), emptyList()), mapOf("Sample" to "Sample"))

    private fun compile(name: String, body: String = "return \"ok\";", declarations: String = ""): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("Sample.java")
        Files.writeString(source, "public class Sample { public String run() { $body } $declarations }")
        val classes = Files.createDirectories(root.resolve("output"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }
}
