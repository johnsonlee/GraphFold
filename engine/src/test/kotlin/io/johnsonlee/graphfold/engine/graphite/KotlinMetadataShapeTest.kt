package io.johnsonlee.graphfold.engine.graphite

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class KotlinMetadataShapeTest {
    @TempDir lateinit var temporary: Path
    private var compilation = 0

    @Test
    fun `private method and property churn regenerate identical public metadata string tables`() {
        val before = metadata("class Example { private val oldField = 1; private fun oldName() = 2; fun run() = 3 }")
        val after = metadata("class Example { private val newField = 1; private fun newName() = 2; fun run() = 3 }")
        val added = metadata("class Example { private fun extra() = 4; private val additional = 5; fun run() = 3 }")
        assertNotEquals(before.values, after.values)
        assertEquals(normalized(before), normalized(after))
        assertEquals(normalized(before), normalized(added))
    }

    @Test
    fun `public internal protected and parameter contract differences survive normalization`() {
        val signatures = listOf(
            "fun run(value: String): String = value",
            "fun run(value: String?): String? = value",
            "fun run(renamed: String): String = renamed",
            "fun run(value: String = \"default\"): String = value",
            "internal fun run(value: String): String = value",
            "protected fun run(value: String): String = value",
            "inline fun run(value: String): String = value",
        )
        val baseline = normalized(metadata("open class Example { ${signatures.first()} }"))
        signatures.drop(1).forEach { signature ->
            assertNotEquals(baseline, normalized(metadata("open class Example { $signature }")), signature)
        }
    }

    @Test
    fun `file facade and multifile part keep public metadata while dropping private declarations`() {
        val plain = normalized(metadata("fun run() = 1\nprivate fun old() = 2", "ExampleKt"))
        assertEquals(plain, normalized(metadata("fun run() = 1\nprivate fun renamed() = 2", "ExampleKt")))
        val prefix = "@file:JvmName(\"Facade\")\n@file:JvmMultifileClass\n"
        val before = metadata(prefix + "fun run() = 1\nprivate fun old() = 2", "Facade__ExampleKt")
        val after = metadata(prefix + "fun run() = 1\nprivate fun renamed() = 2", "Facade__ExampleKt")
        assertEquals(normalized(before), normalized(after))
    }

    @Test
    fun `other metadata fields are preserved and valid synthetic metadata remains raw`() {
        val annotation = metadata("class Example { private fun helper() = 1; fun run() = 2 }")
        annotation.values.addAll(listOf("unknownExtension", "preserve"))
        val normalized = assertNotNull(KotlinMetadataShape.normalize(annotation))
        assertEquals("preserve", normalized.values.last())
        val synthetic = AnnotationNode("Lkotlin/Metadata;").also {
            it.values = mutableListOf("k", 3, "mv", listOf(2, 1, 0), "xi", 48)
        }
        assertSame(synthetic, KotlinMetadataShape.normalize(synthetic))
        val other = AnnotationNode("Lsample/Other;")
        assertSame(other, KotlinMetadataShape.normalize(other))
    }

    @Test
    fun `unknown versions and malformed metadata cannot be silently normalized`() {
        fun annotation(vararg values: Any) = AnnotationNode("Lkotlin/Metadata;").also { it.values = values.toMutableList() }
        assertNull(KotlinMetadataShape.normalize(annotation("k", 1, "mv", listOf(99, 0, 0))))
        assertNull(KotlinMetadataShape.normalize(annotation("k", 1, "mv", "invalid")))
        assertNull(KotlinMetadataShape.normalize(annotation("k", 1, "mv", listOf(2, 1, 0), "d1", listOf(1))))
        assertNull(KotlinMetadataShape.normalize(annotation("k")))
    }

    @Test
    fun `array and list annotation encodings preserve the same public contract`() {
        val original = metadata("class Example { val value: Int = 1 }")
        val arrays = AnnotationNode(original.desc).also { it.values = original.values.toMutableList() }
        for (index in arrays.values.indices.step(2)) {
            when (arrays.values[index]) {
                "mv" -> arrays.values[index + 1] = (arrays.values[index + 1] as List<*>).map { it as Int }.toIntArray()
                "d1", "d2" -> arrays.values[index + 1] = (arrays.values[index + 1] as List<*>).map { it as String }.toTypedArray()
            }
        }
        val expected = assertNotNull(KotlinMetadataShape.normalize(original))
        val actual = assertNotNull(KotlinMetadataShape.normalize(arrays))
        for (key in listOf("d1", "d2")) {
            assertEquals(expected.values[expected.values.indexOf(key) + 1], actual.values[actual.values.indexOf(key) + 1])
        }
    }

    @Test
    fun `missing and invalid metadata version fields remain unprovable`() {
        val versions = listOf(null, emptyList<Int>(), listOf(2, 2, 0), listOf("2", 1, 0), listOf(99))
        for (version in versions) {
            val annotation = AnnotationNode("Lkotlin/Metadata;").also {
                it.values = mutableListOf("k", 3, "mv", version)
            }
            assertNull(KotlinMetadataShape.normalize(annotation), "version=$version")
        }
        assertNull(KotlinMetadataShape.normalize(AnnotationNode("Lkotlin/Metadata;")))
    }

    @Test
    fun `invalid metadata keys and string encodings are blocked`() {
        val malformed = listOf(
            listOf(17, "value"),
            listOf("mv", listOf(2, 1, 0), "d1", 42),
            listOf("mv", listOf(2, 1, 0), "d1", arrayOf<Any>(42)),
            listOf("mv", listOf(2, 1, 0), "d2", listOf(42)),
        )
        malformed.forEach { values ->
            val annotation = AnnotationNode("Lkotlin/Metadata;").also { it.values = values.toMutableList() }
            assertNull(KotlinMetadataShape.normalize(annotation))
        }
    }

    @Test
    fun `legacy synthetic and multifile facade metadata are retained without invented declarations`() {
        for (version in listOf(listOf(1, 9, 0), listOf(2, 0, 0))) {
            val synthetic = AnnotationNode("Lkotlin/Metadata;").also {
                it.values = mutableListOf("k", 3, "mv", version)
            }
            assertSame(synthetic, KotlinMetadataShape.normalize(synthetic))
        }
        val truncatedVersion = AnnotationNode("Lkotlin/Metadata;").also {
            it.values = mutableListOf("k", 3, "mv", listOf(2))
        }
        assertNull(KotlinMetadataShape.normalize(truncatedVersion))
        val facade = metadata("@file:JvmName(\"Facade\")\n@file:JvmMultifileClass\nfun run() = 1", "Facade")
        assertSame(facade, KotlinMetadataShape.normalize(facade))
    }

    @Test
    fun `package and extra string metadata survive private member normalization`() {
        val annotation = metadata("class Example { private fun helper() = 1; fun run() = 2 }")
        annotation.values.addAll(listOf("pn", "sample.contract", "xs", "custom-owner"))
        val normalized = assertNotNull(KotlinMetadataShape.normalize(annotation))
        for (key in listOf("pn", "xs")) {
            assertEquals(annotation.values[annotation.values.indexOf(key) + 1],
                normalized.values[normalized.values.indexOf(key) + 1])
        }
    }

    private fun normalized(annotation: AnnotationNode): List<Any> = assertNotNull(KotlinMetadataShape.normalize(annotation)).values

    private fun metadata(source: String, owner: String = "Example"): AnnotationNode {
        val directory = Files.createDirectory(temporary.resolve("compile-${compilation++}"))
        val file = directory.resolve("Example.kt")
        Files.writeString(file, source)
        val classes = Files.createDirectory(directory.resolve("classes"))
        val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val output = ByteArrayOutputStream()
        val result = PrintStream(output).use { stream ->
            K2JVMCompiler().exec(stream, "-no-stdlib", "-no-reflect", "-classpath", library,
                "-jvm-target", "17", "-d", classes.toString(), file.toString())
        }
        assertEquals(ExitCode.OK, result, output.toString())
        val node = ClassNode()
        ClassReader(Files.readAllBytes(classes.resolve("$owner.class"))).accept(node, ClassReader.SKIP_CODE)
        return node.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }
    }
}
