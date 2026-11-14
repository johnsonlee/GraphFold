package io.johnsonlee.graphfold.engine.inputs

import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.RepositoryConfiguration
import io.johnsonlee.graphfold.engine.Verdict
import io.johnsonlee.graphfold.engine.inputs.archive.InputFailure
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StaticInputSnapshotsTest {
    @TempDir lateinit var temporary: Path

    @Test fun `exported bytes survive later compilation and bundle relocation`() {
        val repository = repository()
        val artifact = archive(1)
        val original = Files.readAllBytes(artifact)
        val bundle = temporary.resolve("bundle")
        val manifest = StaticInputSnapshots.write(repository, bundle.resolve("input.json"),
            listOf(ArchiveScopeInput("library", artifact, javaVersion = 17)))
        archive(2)
        assertContentEquals(original, Files.readAllBytes(readSnapshot(manifest).scopes.single().archive))
        val moved = Files.move(bundle, temporary.resolve("moved"))
        assertContentEquals(original, Files.readAllBytes(readSnapshot(moved.resolve("input.json")).scopes.single().archive))
    }

    @Test fun `unrecorded assets cannot change a committed snapshot`() {
        val manifest = StaticInputSnapshots.write(repository(), temporary.resolve("input.json"),
            listOf(ArchiveScopeInput("library", archive(1), javaVersion = 17)))
        val record = readSnapshot(manifest)
        Files.writeString(record.scopes.single().archive.resolveSibling("extra.class"), "unrecorded")
        assertTrue(assertFailsWith<InputFailure> { readSnapshot(manifest) }.message.contains("inventory changed"))
    }

    @Test fun `same revision cannot excuse a changed packaged input`() {
        val repository = repository()
        val before = StaticInputSnapshots.write(repository, temporary.resolve("before.json"),
            listOf(ArchiveScopeInput("library", archive(1), javaVersion = 17)))
        val unchanged = StaticInputSnapshots.write(repository, temporary.resolve("unchanged.json"),
            listOf(ArchiveScopeInput("library", archive(1), javaVersion = 17)))
        val configuration = RepositoryConfiguration(temporary.resolve("cache"))
        assertEquals(Verdict.PASS, StaticInputSnapshots.analyze(repository.toString(), before, unchanged, RULES, configuration).verdict)
        val changed = StaticInputSnapshots.write(repository, temporary.resolve("changed.json"),
            listOf(ArchiveScopeInput("library", archive(2), javaVersion = 17)))
        assertTrue(assertFailsWith<InputFailure> {
            StaticInputSnapshots.analyze(repository.toString(), before, changed, RULES, configuration)
        }.message.contains("same Git revision"))
    }

    @Test fun `malformed supplied origins cannot borrow the unchanged bytecode shortcut`() {
        val repository = repository()
        val artifact = archive(1)
        val base = StaticInputSnapshots.write(repository, temporary.resolve("base/input.json"),
            listOf(ArchiveScopeInput("library", artifact, javaVersion = 17)))
        Files.writeString(repository.resolve("README.md"), "Changed documentation only")
        Git.open(repository.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("documentation").setAuthor("GraphFold", "test@example.invalid")
                .setCommitter("GraphFold", "test@example.invalid").call()
        }
        val configuration = RepositoryConfiguration(temporary.resolve("cache"))
        val unchanged = StaticInputSnapshots.write(repository, temporary.resolve("unchanged/input.json"),
            listOf(ArchiveScopeInput("library", artifact, javaVersion = 17)))
        val valid = StaticInputSnapshots.analyze(repository.toString(), base, unchanged, RULES, configuration)
        assertEquals(Verdict.PASS, valid.verdict, valid.toString())
        assertTrue(valid.diagnostics.isEmpty(), valid.toString())
        val origins = Files.createDirectory(temporary.resolve("origins"))
        Files.writeString(origins.resolve("compiler.json"), "{}")
        val corrupt = StaticInputSnapshots.write(repository, temporary.resolve("corrupt/input.json"),
            listOf(ArchiveScopeInput("library", artifact, originDirectory = origins, originRunId = "fixture", javaVersion = 17)))
        val invalid = StaticInputSnapshots.analyze(repository.toString(), base, corrupt, RULES, configuration)
        assertEquals(Verdict.FAIL, invalid.verdict, invalid.toString())
        assertTrue(invalid.diagnostics.any { it.blocking && it.code == DiagnosticCode.UNVERIFIED_ORIGIN }, invalid.toString())
        assertTrue(invalid.findings.isEmpty(), invalid.toString())
    }

    private fun repository(): Path {
        val root = Files.createDirectories(temporary.resolve("repository"))
        Files.writeString(root.resolve("README.md"), "Committed input identity")
        Git.init().setDirectory(root.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("input identity").setAuthor("GraphFold", "test@example.invalid")
                .setCommitter("GraphFold", "test@example.invalid").call()
        }
        return root
    }

    private fun archive(value: Int): Path {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Library", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
                visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val path = temporary.resolve("library.jar")
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("Library.class")); jar.write(bytes); jar.closeEntry()
        }
        return path
    }

    private companion object {
        const val RULES = "version: 1\nfolds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n"
    }
}
