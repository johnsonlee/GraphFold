package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.ResourceDeclarations

import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode

class ResourceFieldInitializationTest {
    @TempDir lateinit var directory: Path

    @Test fun `field through implementing class initializes declaring nondefault interface resource reader`() {
        val source = directory.resolve("App.java")
        Files.writeString(source, """
            interface Values {
                String TEXT = java.util.ResourceBundle.getBundle("messages").getString("changed");
            }
            class Impl implements Values {}
            public class App { public static String run() { return Values.TEXT; } }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), source.toString()))
        val app = classes.resolve("App.class")
        val node = ClassNode()
        ClassReader(Files.readAllBytes(app)).accept(node, 0)
        node.methods.flatMap { it.instructions.toArray().toList() }.filterIsInstance<FieldInsnNode>()
            .filter { it.owner == "Values" }.forEach { it.owner = "Impl" }
        val writer = ClassWriter(0)
        node.accept(writer)
        Files.write(app, writer.toByteArray())
        val declarations = ResourceDeclarations(listOf(classes))
        assertEquals("Values", declarations.fieldOwner("Impl.TEXT:Ljava/lang/String;"))
        assertFalse("Values" in declarations.initialization().getValue("Impl"))
        val sink = SnapshotSink(applicationOwners = declarations.classes, dependencyResources = true)
        val graph = JavaProjectLoader(LoaderConfig(includeLibraries = true, interproceduralDataflow = true), false)
            .loadObserved(classes, sink::capture).graph
        val analysis = DependencyResourceAnalysis(graph, sink.methods, sink.resourceCalls, declarations.initialization(),
            sink.resourceInitializers, declarations::fieldOwner)
        assertTrue(ResourceRead("messages", "changed") in analysis.reads("App.run()Ljava/lang/String;"))
        assertTrue(analysis.reads("Impl.<init>()V").isEmpty(), "Constructing the implementing class does not initialize Values")
    }

    @Test fun `full analyzer uses pinned Intrinsics resource contract while retaining application resource reads`() {
        val pinned = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val dependency = directory.resolve("pinned-intrinsics.jar")
        // Copy real pinned definitions unchanged, including the exception classes referenced by Intrinsics.
        // This fixture needs the contract's dependency closure, not the unrelated stdlib APIs.
        JarFile(pinned.toFile()).use { source ->
            JarOutputStream(Files.newOutputStream(dependency)).use { target ->
                listOf("kotlin/jvm/internal/Intrinsics", "kotlin/KotlinNullPointerException", "kotlin/TypeCastException",
                    "kotlin/UninitializedPropertyAccessException").forEach { owner ->
                    val name = "$owner.class"
                    target.putNextEntry(JarEntry(name))
                    target.write(pinnedClassBytes(source, name))
                    target.closeEntry()
                }
            }
        }
        val file = Files.writeString(directory.resolve("App.java"), """
            public class App {
                public static String run() {
                    kotlin.jvm.internal.Intrinsics.checkNotNullParameter("value", "name");
                    return java.util.ResourceBundle.getBundle("messages").getString("changed");
                }
            }
        """.trimIndent())
        val application = Files.createDirectories(directory.resolve("pinned-app"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-cp", dependency.toString(), "-d", application.toString(), file.toString()))
        assertTrue(ResourceDeclarations(listOf(dependency)).trustedIntrinsics)
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        assertTrue(snapshot.resourceDependenciesAnalyzed, snapshot.diagnostics.toString())
        assertEquals(listOf(ResourceRead("messages", "changed")), snapshot.methods.getValue("App.run()Ljava/lang/String;").resourceReads)
        assertTrue(snapshot.methods.keys.none { it.startsWith("kotlin.") }, "Pinned library methods remain dependencies")
    }

    private fun pinnedClassBytes(archive: JarFile, name: String): ByteArray =
        archive.getInputStream(requireNotNull(archive.getJarEntry(name))).use { it.readBytes() }

    @Test fun `lookalike Intrinsics contract cannot hide a dependency resource reader`() {
        val fake = directory.resolve("Intrinsics.java")
        Files.writeString(fake, """
            package kotlin.jvm.internal;
            public class Intrinsics {
                public static void checkNotNullParameter(Object value, String name) {
                    java.util.ResourceBundle.getBundle("messages").getString("changed");
                }
            }
        """.trimIndent())
        val app = directory.resolve("App.java")
        Files.writeString(app, """
            public class App {
                public static void run() { kotlin.jvm.internal.Intrinsics.checkNotNullParameter("value", "name"); }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("fake-classes"))
        val application = Files.createDirectories(directory.resolve("app-classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), fake.toString()))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-cp", classes.toString(), "-d", application.toString(), app.toString()))
        assertFalse(ResourceDeclarations(listOf(classes)).trustedIntrinsics)
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(classes), dependencyResources = true)
        assertTrue(ResourceRead("messages", "changed") in snapshot.methods.getValue("App.run()V").resourceReads)
        assertTrue(snapshot.methods.keys.none { it.startsWith("kotlin.") }, "Dependency methods are not application roots")
    }
}
