package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationArchiveOwnershipTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["jar", "boot", "war"])
    fun `project library membership preserves graph classes independently of source mapping`(layout: String) {
        val libraryBytes = bytecode("business/Quote")
        val library = projectionArchive(temporary.resolve("library.jar"), mapOf("business/Quote.class" to libraryBytes))
        val external = projectionArchive(temporary.resolve("external.jar"), mapOf("external/Api.class" to bytecode("external/Api")))
        val sibling = projectionArchive(temporary.resolve("sibling.jar"), mapOf("sibling/Unused.class" to bytecode("sibling/Unused")))
        val prefix = when (layout) { "boot" -> "BOOT-INF/"; "war" -> "WEB-INF/"; else -> "" }
        val applicationBytes = bytecode("sample/App")
        val contents = if (prefix.isEmpty()) mapOf("sample/App.class" to applicationBytes) else mapOf(
            "${prefix}classes/sample/App.class" to applicationBytes,
            "${prefix}lib/library.jar" to Files.readAllBytes(library),
            "${prefix}lib/external.jar" to Files.readAllBytes(external))
        val application = projectionArchive(temporary.resolve(if (layout == "war") "app.war" else "app.jar"), contents)
        val source = Files.createDirectories(temporary.resolve("source"))
        val compiler = Files.createDirectories(temporary.resolve("compiler"))
        val provenance = InputProject(compiler, listOf(source), emptyList(), sourceOutputs = mapOf(source to listOf(compiler)))
        listOf(false, true).forEach { mapped ->
            val selected = if (layout == "jar") listOf(application, library, external) else listOf(application)
            val entries = selected.map { artifact ->
                ArchiveEntryInput(artifact, if (mapped && artifact == library) listOf(compiler) else emptyList())
            }
            val archive = ArchiveInput("app", entries,
                applicationArchives = setOf(library, sibling),
                producerArchives = if (mapped) mapOf(library to listOf(compiler)) else emptyMap())
            val result = prepareArchiveInput(archive, provenance, temporary.resolve("prepared-$mapped"))
            val classes = Files.walk(result.classes).use { paths -> paths.filter(Files::isRegularFile)
                .map { result.classes.relativize(it).joinToString("/") }.toList().toSet() }
            assertEquals(setOf("sample/App.class", "business/Quote.class"), classes)
            assertContentEquals(applicationBytes, Files.readAllBytes(result.classes.resolve("sample/App.class")))
            assertContentEquals(libraryBytes, Files.readAllBytes(result.classes.resolve("business/Quote.class")))
            assertEquals(if (mapped) listOf(source.toRealPath()) else emptyList(), result.sourceRoots)
            assertTrue(result.dependencies.any { readWarArchive(it).containsKey("external/Api.class") })
        }
    }

    @Test fun `unmapped primary retains its classes alongside a mapped project library`() {
        val appBytes = bytecode("sample/App")
        val libraryBytes = bytecode("business/Quote")
        val app = projectionArchive(temporary.resolve("app.jar"), mapOf("sample/App.class" to appBytes))
        val library = projectionArchive(temporary.resolve("library.jar"), mapOf("business/Quote.class" to libraryBytes))
        val compiler = Files.createDirectories(temporary.resolve("compiler"))
        val selected = ArchiveInput("app", listOf(ArchiveEntryInput(app), ArchiveEntryInput(library, listOf(compiler))))
        val result = prepareArchiveInput(selected, InputProject(compiler, emptyList(), emptyList()), temporary.resolve("prepared"))
        assertContentEquals(appBytes, Files.readAllBytes(result.classes.resolve("sample/App.class")))
        assertContentEquals(libraryBytes, Files.readAllBytes(result.classes.resolve("business/Quote.class")))
    }

    @Test fun `copied project archive remains application code without compiler source associations`() {
        val appBytes = bytecode("sample/App")
        val libraryBytes = bytecode("business/Quote")
        val app = projectionArchive(temporary.resolve("app.jar"), mapOf("sample/App.class" to appBytes))
        val producer = projectionArchive(temporary.resolve("producer/library.jar"), mapOf("business/Quote.class" to libraryBytes))
        val packaged = Files.copy(producer, temporary.resolve("packaged-library.jar"))
        val compiler = Files.createDirectories(temporary.resolve("compiler"))
        val selected = ArchiveInput("app", listOf(ArchiveEntryInput(app), ArchiveEntryInput(packaged)),
            applicationArchives = setOf(producer))
        val result = prepareArchiveInput(selected, InputProject(compiler, emptyList(), emptyList()), temporary.resolve("prepared"))
        assertContentEquals(appBytes, Files.readAllBytes(result.classes.resolve("sample/App.class")))
        assertContentEquals(libraryBytes, Files.readAllBytes(result.classes.resolve("business/Quote.class")))
        assertTrue(result.sourceRoots.isEmpty() && result.sourceOutputs.isEmpty() && result.compilerEvidence.isEmpty())
        assertTrue(result.dependencies.none { readWarArchive(it).containsKey("business/Quote.class") })
    }

    private fun bytecode(owner: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
