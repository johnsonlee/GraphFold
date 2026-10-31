package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OriginBytecodeNormalizerTest {
    @TempDir lateinit var temporary: Path

    @Test fun `actual anonymous classes and nested constructors remap without rewriting user text`() {
        val source = temporary.resolve("Host.java")
        Files.writeString(source, """
            package sample;
            public class Host {
                public Object create() { return new Runnable() {
                    class Nested {}
                    public void run() { new Nested(); }
                    public String literal() { return "sample.Host${'$'}1"; }
                }; }
            }
        """.trimIndent())
        val classes = Files.createDirectories(temporary.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-d", classes.toString(), source.toString()))
        val original = read(classes.resolve("sample/Host\$1.class"))
        original.invisibleAnnotations = mutableListOf(
            AnnotationNode(ORIGIN_MARKER), AnnotationNode("Lsample/Label;").also {
                it.values = mutableListOf("text", "sample.Host\$1", "type", Type.getObjectType("sample/Host\$1"))
            },
        )
        write(classes.resolve("sample/Host\$1.class"), original)
        val result = normalizeOriginClasses(classes, temporary.resolve("analysis"), mapOf("sample.Host\$1" to "sample.Stable"))
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals("sample.Stable\$Nested", result.owners["sample.Host\$1\$Nested"])
        val renamed = read(result.classes.resolve("sample/Stable.class"))
        assertTrue(renamed.invisibleAnnotations.none { it.desc == ORIGIN_MARKER })
        assertTrue(read(classes.resolve("sample/Host\$1.class")).invisibleAnnotations.any { it.desc == ORIGIN_MARKER })
        val user = renamed.invisibleAnnotations.single { it.desc == "Lsample/Label;" }
        assertEquals("sample.Host\$1", user.values[user.values.indexOf("text") + 1])
        assertEquals(Type.getObjectType("sample/Stable"), user.values[user.values.indexOf("type") + 1])
        assertTrue(renamed.methods.flatMap { it.instructions.filterIsInstance<LdcInsnNode>() }
            .any { it.cst == "sample.Host\$1" })
        assertTrue(renamed.methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }
            .any { it.owner == "sample/Stable\$Nested" && it.desc == "(Lsample/Stable;)V" })
        assertTrue(renamed.innerClasses.any { it.name == "sample/Stable" && it.innerName == null })
        assertEquals(original.outerMethod, renamed.outerMethod)
        val host = read(result.classes.resolve("sample/Host.class"))
        assertFalse(host.innerClasses.any { it.name == "sample/Stable" })
        assertFalse(host.nestMembers.orEmpty().contains("sample/Stable"))
    }

    @Test fun `Kotlin metadata uses typed class names while unrelated annotation strings survive`() {
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val kmClass = KmClass().apply {
            name = ".sample/Host\$1"
            supertypes += KmType().apply { classifier = KmClassifier.Class("kotlin/Any") }
        }
        val metadata = KotlinClassMetadata.Class(kmClass, JvmMetadataVersion(2, 1, 0), 0).write()
        val node = simpleClass("sample/Host\$1")
        node.visibleAnnotations = mutableListOf(annotation(metadata), AnnotationNode("Lsample/Label;").also {
            it.values = mutableListOf("value", "sample/Host\$1")
        })
        write(classes.resolve("sample/Host\$1.class"), node)
        val result = normalizeOriginClasses(classes, temporary.resolve("analysis"), mapOf("sample.Host\$1" to "sample.Stable"))
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val renamed = read(result.classes.resolve("sample/Stable.class"))
        val attributes = renamed.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }.values.chunked(2)
            .associate { it[0] as String to it[1] }
        @Suppress("UNCHECKED_CAST")
        val rewritten = Metadata(kind = 1, metadataVersion = intArrayOf(2, 1, 0),
            data1 = (attributes.getValue("d1") as List<String>).toTypedArray(),
            data2 = (attributes.getValue("d2") as List<String>).toTypedArray())
        val decoded = KotlinClassMetadata.readStrict(rewritten) as KotlinClassMetadata.Class
        assertEquals(".sample/Stable", decoded.kmClass.name)
        assertEquals("sample/Host\$1", renamed.visibleAnnotations.single { it.desc == "Lsample/Label;" }.values.last())
    }

    @Test fun `unknown metadata schemas block normalization instead of textual replacement`() {
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val node = simpleClass("sample/Host\$1")
        node.visibleAnnotations = mutableListOf(AnnotationNode("Lkotlin/Metadata;").also {
            it.values = mutableListOf("k", 1, "mv", listOf(99, 0, 0), "d2", listOf("sample/Host\$1"))
        })
        write(classes.resolve("sample/Host\$1.class"), node)
        val result = normalizeOriginClasses(classes, temporary.resolve("analysis"), mapOf("sample.Host\$1" to "sample.Stable"))
        assertTrue(result.diagnostics.single().contains("Metadata schema"))
        val values = read(result.classes.resolve("sample/Stable.class")).visibleAnnotations.single().values
        assertEquals(listOf("sample/Host\$1"), values[values.indexOf("d2") + 1])
    }

    @Test fun `rendering unknown objects and serializable anonymous classes blocks identity normalization`() {
        val node = simpleClass("sample/Host\$1")
        node.interfaces.add("java/io/Serializable")
        val method = org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "render", "()V", null, null)
        method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
            "(Ljava/lang/Object;)Ljava/lang/String;", false))
        node.methods.add(method)
        val diagnostics = originNameObservability(listOf(node), mapOf("sample.Host\$1" to "sample.Stable"))
        assertTrue(diagnostics.any { "Serializable" in it })
        assertTrue(diagnostics.any { "object rendering" in it })
    }

    @Test fun `primitive and string rendering cannot expose anonymous binary names`() {
        val node = simpleClass("sample/Host\$1")
        val method = org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "render", "()V", null, null)
        method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
            "(I)Ljava/lang/String;", false))
        method.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString",
            "()Ljava/lang/String;", false))
        node.methods.add(method)
        assertTrue(originNameObservability(listOf(node), mapOf("sample.Host\$1" to "sample.Stable")).isEmpty())
    }

    @Test fun `formatters array rendering and Kotlin stdlib rendering remain observable`() {
        val calls = listOf(
            Triple("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"),
            Triple("java/io/PrintStream", "printf", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintStream;"),
            Triple("java/util/Formatter", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/Formatter;"),
            Triple("java/util/Arrays", "deepToString", "([Ljava/lang/Object;)Ljava/lang/String;"),
            Triple("kotlin/collections/CollectionsKt", "joinToString", "(Ljava/lang/Iterable;)Ljava/lang/String;"),
            Triple("kotlin/jvm/internal/Intrinsics", "stringPlus", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"),
            Triple("kotlin/io/ConsoleKt", "println", "(Ljava/lang/Object;)V"),
        )
        calls.forEach { (owner, name, descriptor) ->
            val node = simpleClass("sample/Host\$1")
            val method = org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "render", "()V", null, null)
            method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, descriptor, false))
            node.methods.add(method)
            assertTrue(originNameObservability(listOf(node), mapOf("sample.Host\$1" to "sample.Stable"))
                .any { "object rendering" in it }, "$owner.$name")
        }
    }

    @Test fun `normalization rejects destructive destinations and reports canonical owner collisions`() {
        val classes = Files.createDirectories(temporary.resolve("classes"))
        assertFailsWith<IllegalArgumentException> { normalizeOriginClasses(temporary.resolve("missing"), classes, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { normalizeOriginClasses(classes, classes, emptyMap()) }
        write(classes.resolve("sample/First.class"), simpleClass("sample/First"))
        write(classes.resolve("sample/Second.class"), simpleClass("sample/Second"))
        val result = normalizeOriginClasses(classes, temporary.resolve("analysis"),
            mapOf("sample.First" to "sample.Target", "sample.Second" to "sample.Target"))
        assertTrue(result.diagnostics.single().contains("duplicate JVM owner"))
        assertTrue(Files.exists(classes.resolve("sample/First.class")))
        assertTrue(Files.exists(classes.resolve("sample/Second.class")))
    }

    @Test fun `compiler nesting without a conventional binary prefix remains unresolved`() {
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val parent = simpleClass("sample/Host\$1")
        val nested = simpleClass("sample/Unconventional").apply {
            outerClass = parent.name
            innerClasses.add(org.objectweb.asm.tree.InnerClassNode(name, parent.name, "Nested", 0))
        }
        write(classes.resolve("sample/Host\$1.class"), parent)
        write(classes.resolve("sample/Unconventional.class"), nested)
        val result = normalizeOriginClasses(classes, temporary.resolve("analysis"), mapOf("sample.Host\$1" to "sample.Stable"))
        assertTrue(result.diagnostics.any { "unsupported binary layout" in it })
        assertFalse("sample.Unconventional" in result.owners)
    }

    private fun simpleClass(name: String) = ClassNode().apply {
        version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; this.name = name; superName = "java/lang/Object"
    }
    private fun annotation(metadata: Metadata) = AnnotationNode("Lkotlin/Metadata;").also {
        it.values = mutableListOf("k", metadata.kind, "mv", metadata.metadataVersion.toList(),
            "d1", metadata.data1.toList(), "d2", metadata.data2.toList(), "xi", metadata.extraInt)
    }
    private fun read(path: Path) = ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) }
    private fun write(path: Path, node: ClassNode) {
        Files.createDirectories(path.parent)
        val writer = ClassWriter(0)
        node.accept(writer)
        Files.write(path, writer.toByteArray())
    }
}
