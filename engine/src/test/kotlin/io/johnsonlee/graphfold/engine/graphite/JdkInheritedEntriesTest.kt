package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.BytecodeInventory

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassVisitor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Actual javac output verifies that system-module declaration lookup preserves inherited JVM signatures. */
class JdkInheritedEntriesTest {
    @TempDir lateinit var temporary: Path

    @Test fun `compiler plugin scanner and listener overrides resolve from jdk compiler without application dependencies`() {
        val classes = compile("CompilerHooks", """
            package sample;
            import com.sun.source.tree.IdentifierTree;
            import com.sun.source.util.*;
            public class CompilerHooks extends TreePathScanner<Void, Void> implements Plugin, TaskListener {
                public String getName() { return "test"; }
                public void init(JavacTask task, String... args) { }
                public void started(TaskEvent event) { }
                public void finished(TaskEvent event) { }
                public Void visitIdentifier(IdentifierTree tree, Void value) { return null; }
            }
        """.trimIndent())
        val inventory = BytecodeInventory().also { it.read(classes) }
        val diagnostics = mutableListOf<String>()
        val overrides = inventory.inheritedEntries.resolve(emptyList(), diagnostics)
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
        assertTrue(overrides.containsAll(setOf(
            "sample.CompilerHooks.getName()Ljava/lang/String;",
            "sample.CompilerHooks.init(Lcom/sun/source/util/JavacTask;[Ljava/lang/String;)V",
            "sample.CompilerHooks.started(Lcom/sun/source/util/TaskEvent;)V",
            "sample.CompilerHooks.finished(Lcom/sun/source/util/TaskEvent;)V",
            "sample.CompilerHooks.visitIdentifier(Lcom/sun/source/tree/IdentifierTree;Ljava/lang/Object;)Ljava/lang/Object;",
        )), overrides.toString())
        assertTrue(inventory.missingReferences(emptyList()).isEmpty(), inventory.references.toString())
    }

    @Test fun `host classpath dependency absent from analyzed inputs remains an unresolved inherited API and reference`() {
        val dependency = Path.of(ClassVisitor::class.java.protectionDomain.codeSource.location.toURI())
        val classes = compile("ExternalVisitor", """
            package sample;
            public class ExternalVisitor extends org.objectweb.asm.ClassVisitor {
                public ExternalVisitor() { super(org.objectweb.asm.Opcodes.ASM9); }
                public void visitEnd() { }
            }
        """.trimIndent(), listOf("-classpath", dependency.toString()))
        val inventory = BytecodeInventory().also { it.read(classes) }
        val diagnostics = mutableListOf<String>()
        assertTrue(inventory.inheritedEntries.resolve(emptyList(), diagnostics).isEmpty())
        assertTrue(diagnostics.any { "org/objectweb/asm/ClassVisitor" in it }, diagnostics.toString())
        assertTrue("org.objectweb.asm.ClassVisitor" in inventory.missingReferences(emptyList()))
        val resolvedDiagnostics = mutableListOf<String>()
        val resolved = inventory.inheritedEntries.resolve(listOf(dependency), resolvedDiagnostics)
        assertTrue(resolvedDiagnostics.isEmpty(), resolvedDiagnostics.toString())
        assertTrue("sample.ExternalVisitor.visitEnd()V" in resolved, resolved.toString())
        assertTrue(inventory.missingReferences(listOf(dependency)).isEmpty())
    }

    private fun compile(name: String, source: String, classpath: List<String> = emptyList()): Path {
        val file = Files.writeString(temporary.resolve("$name.java"), source)
        val output = Files.createDirectory(temporary.resolve("classes"))
        val arguments = listOf("--release", "17", "-d", output.toString()) + classpath + file.toString()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        return output
    }
}
