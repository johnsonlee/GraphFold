package io.johnsonlee.graphfold.engine.inputs.archive

import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.InputChangeProof
import io.johnsonlee.graphfold.engine.git.changedFile
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaticClasspathOrderTest {
    @TempDir lateinit var root: Path
    private var sequence = 0

    @Test fun `complete Boot index selects annotated bytes ahead of ZIP and filename order`() {
        val first = library("a", definition(1))
        val indexed = library("z", definition(1, "indexed"))
        val boot = boot(first, indexed, "- \"BOOT-INF/lib/z.jar\"\n- \"BOOT-INF/lib/a.jar\"\n")
        val prepared = prepare(ArchiveInput("boot", listOf(ArchiveEntryInput(boot))))
        assertContentEquals(definition(1, "indexed"), selected(prepared, "sample/Value.class"))
        assertTrue(prepared.production!!.nonClassEntries.keys.any { it.endsWith("BOOT-INF/classpath.idx") })
    }

    @Test fun `blank lines preserve the declared Boot index order`() {
        val first = library("a", definition(1))
        val indexed = library("z", definition(2))
        val archive = boot(first, indexed, "\n  \n- \"BOOT-INF/lib/z.jar\"\n\t\n- \"BOOT-INF/lib/a.jar\"\n")
        assertContentEquals(definition(2), selected(prepare(ArchiveInput("boot", listOf(ArchiveEntryInput(archive)))),
            "sample/Value.class"))
    }

    @Test fun `WAR accepts an explicitly provided API archive without a container or index`() {
        val dependency = library("api", definition(7))
        val archive = projectionArchive(root.resolve("app.war"),
            mapOf("WEB-INF/classes/sample/App.class" to definition(0, owner = "sample/App")))
        val input = ArchiveInput("war", listOf(ArchiveEntryInput(archive)), staticDependencies = listOf(dependency))
        assertContentEquals(definition(7), selected(prepare(input), "sample/Value.class"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `explicit integration order selects first library independently of names`(reverse: Boolean) {
        val primary = primary()
        val libraries = listOf(library("z", definition(1)), library("a", definition(2)))
        val ordered = if (reverse) libraries.reversed() else libraries
        val prepared = prepare(ArchiveInput("ordered", (listOf(primary) + ordered).map(::ArchiveEntryInput)))
        assertContentEquals(definition(if (reverse) 2 else 1), selected(prepared, "sample/Value.class"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `later owned copy retains role only when it matches selected bytes`(identical: Boolean) {
        val external = library("external", definition(1))
        val owned = library("owned", definition(if (identical) 1 else 2))
        val prepared = prepare(ArchiveInput("ownership", listOf(ArchiveEntryInput(primary()), ArchiveEntryInput(external),
            ArchiveEntryInput(owned, application = true))))
        assertEquals(identical, Files.exists(prepared.classes.resolve("sample/Value.class")))
        assertContentEquals(definition(1), selected(prepared, "sample/Value.class"))
        val inventory = requireNotNull(prepared.production)
        assertEquals(!identical, "artifact/1/sample/Value.class" in inventory.nonClassEntries)
    }

    @Test fun `later owned multi release copy uses the build selected definition for ownership`() {
        val external = library("external", definition(17))
        val owned = projectionArchive(root.resolve("owned.jar"), mapOf("sample/Value.class" to definition(8),
            "META-INF/versions/17/sample/Value.class" to definition(17)), mapOf("Multi-Release" to "true"))
        val prepared = prepare(ArchiveInput("ownership", listOf(ArchiveEntryInput(primary()), ArchiveEntryInput(external),
            ArchiveEntryInput(owned, application = true))))
        assertContentEquals(definition(17), Files.readAllBytes(prepared.classes.resolve("sample/Value.class")))
        assertFalse("artifact/1/sample/Value.class" in prepared.production!!.nonClassEntries)
    }

    @ParameterizedTest
    @ValueSource(strings = ["body", "annotation", "resource"])
    fun `shadowed production changes retain an undischarged obligation`(change: String) {
        val external = library("external", definition(1))
        fun revision(changed: Boolean): InputProject {
            val bytes = definition(if (changed && change == "body") 3 else 2,
                if (changed && change == "annotation") "changed" else "original")
            val owned = library("owned-${sequence++}", bytes,
                if (changed && change == "resource") "changed" else "original")
            return prepare(ArchiveInput("ownership", listOf(ArchiveEntryInput(primary()), ArchiveEntryInput(external),
                ArchiveEntryInput(owned, application = true))))
        }
        val base = revision(false)
        val head = revision(true)
        assertContentEquals(selected(base, "sample/Value.class"), selected(head, "sample/Value.class"))
        val proof = InputChangeProof(listOf(changedFile("Library.java", "Library.java")), base.production, head.production)
        assertFalse(proof.unchanged)
        assertEquals(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED, assertNotNull(proof.gap()).code)
        assertNotNull(proof.gap(resourcesProved = true), "Resource proof must not discard a shadowed class or opaque resource")
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "incomplete", "malformed", "duplicate", "unknown"])
    fun `optional unusable index does not prevent unambiguous Boot input`(mode: String) {
        val index = when (mode) {
            "missing" -> null
            "incomplete" -> "- \"BOOT-INF/lib/a.jar\"\n"
            "malformed" -> "not an index"
            "duplicate" -> "- \"BOOT-INF/lib/a.jar\"\n- \"BOOT-INF/lib/a.jar\"\n"
            else -> "- \"BOOT-INF/lib/unknown.jar\"\n- \"BOOT-INF/lib/z.jar\"\n"
        }
        val first = library("a", definition(1))
        val second = library("z", definition(1))
        val archive = boot(first, second, index)
        val projection = assertNotNull(springBootArchiveInput(ArchiveInput("boot", listOf(ArchiveEntryInput(archive))),
            root.resolve("decode-${sequence++}")))
        assertEquals(StaticClassOrder.UNAMBIGUOUS, projection.classOrder)
        assertContentEquals(definition(1), selected(prepare(ArchiveInput("boot", listOf(ArchiveEntryInput(archive)))),
            "sample/Value.class"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["boot", "war"])
    fun `ZIP position alone does not resolve different definitions without declared order`(kind: String) {
        val first = library("a", definition(1))
        val second = library("z", definition(2))
        val archive = if (kind == "boot") boot(first, second, null) else projectionArchive(root.resolve("app.war"), mapOf(
            "WEB-INF/classes/sample/App.class" to definition(0, owner = "sample/App"),
            "WEB-INF/lib/z.jar" to Files.readAllBytes(second), "WEB-INF/lib/a.jar" to Files.readAllBytes(first)))
        assertFailsWith<InputFailure> { prepare(ArchiveInput(kind, listOf(ArchiveEntryInput(archive)))) }
    }

    private fun primary(): Path = projectionArchive(root.resolve("primary-${sequence++}.jar"),
        mapOf("sample/App.class" to definition(0, owner = "sample/App")))

    private fun library(name: String, bytes: ByteArray, resource: String = "unchanged"): Path =
        projectionArchive(root.resolve("$name.jar"), mapOf("sample/Value.class" to bytes, "data.txt" to resource.toByteArray()))

    private fun boot(first: Path, second: Path, index: String?): Path = projectionArchive(root.resolve("boot-${sequence++}.jar"),
        linkedMapOf("BOOT-INF/lib/a.jar" to Files.readAllBytes(first), "BOOT-INF/lib/z.jar" to Files.readAllBytes(second),
            "BOOT-INF/classes/sample/App.class" to definition(0, owner = "sample/App")) +
            if (index == null) emptyMap() else mapOf("BOOT-INF/classpath.idx" to index.toByteArray()))

    private fun prepare(input: ArchiveInput): InputProject = prepareArchiveInput(input,
        InputProject(root.resolve("unused"), emptyList(), emptyList()), root.resolve("prepared-${sequence++}"), 17)

    private fun selected(input: InputProject, name: String): ByteArray {
        val application = input.classes.resolve(name)
        return if (Files.isRegularFile(application)) Files.readAllBytes(application)
            else input.dependencies.firstNotNullOf { readWarArchive(it)[name] }
    }

    private fun definition(value: Int, annotation: String? = null, owner: String = "sample/Value"): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null)
        if (annotation != null) visitAnnotation("Lsample/Metadata;", false).apply { visit("value", annotation); visitEnd() }
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
