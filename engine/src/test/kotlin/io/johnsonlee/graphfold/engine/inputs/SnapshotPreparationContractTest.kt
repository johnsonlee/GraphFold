package io.johnsonlee.graphfold.engine.inputs

import io.johnsonlee.graphfold.engine.inputs.archive.InputFailure
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.eclipse.jgit.api.Git
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotPreparationContractTest {
    @TempDir lateinit var temporary: Path

    @Test fun `source preparation retains classes and optional associations with directory and archive dependencies`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        Files.write(classes.resolve("Library.class"), bytecode("Library"))
        val dependency = archive("Dependency")
        val resources = Files.createDirectory(temporary.resolve("resources"))
        Files.writeString(resources.resolve("config.properties"), "enabled=true")
        val root = Files.createDirectory(temporary.resolve("source"))
        val input = SourceInput(classes, listOf(root), listOf(dependency, resources), 17)
        val prepared = StaticInputSnapshots.prepareSource("revision", temporary, temporary.resolve("prepared"), input)
        assertTrue(prepared.inventory != null)
        assertEquals(listOf(root), prepared.scopes.values.single().sourceRoots)
        assertEquals(emptyMap(), input.sourceOutputs)
        assertTrue(Files.exists(prepared.scopes.values.single().classes.resolve("Library.class")))
        assertFailsWith<IllegalArgumentException> {
            StaticInputSnapshots.prepareSource("revision", temporary, temporary.resolve("invalid"), input.copy(javaVersion = 0))
        }
    }

    @Test fun `public preparation checks checkout identity and requires an empty output directory`() {
        val repository = repository()
        val manifest = write(repository, "base")
        val output = temporary.resolve("prepared")
        val pair = StaticInputSnapshots.prepare(manifest, manifest, repository, repository, output)
        assertEquals(pair.first.inventory?.entries, pair.second.inventory?.entries)
        assertFailsWith<InputFailure> { StaticInputSnapshots.prepare(manifest, manifest, repository, repository, output) }
        Files.writeString(repository.resolve("README"), "changed")
        commit(repository)
        assertFailsWith<InputFailure> {
            StaticInputSnapshots.prepare(manifest, manifest, repository, repository, temporary.resolve("wrong-sha"))
        }
    }

    @Test fun `snapshot validates scope identities archive version and existing destinations`() {
        val repository = repository()
        val archive = archive("Library")
        fun export(scopes: List<ArchiveScopeInput>) =
            StaticInputSnapshots.write(repository, temporary.resolve("inputs.json"), scopes)
        val scope = ArchiveScopeInput("library", archive, javaVersion = 17)
        assertFailsWith<IllegalArgumentException> { scope.copy(javaVersion = 0) }
        assertFailsWith<InputFailure> { export(emptyList()) }
        assertFailsWith<InputFailure> { export(listOf(scope, scope)) }
        assertFailsWith<InputFailure> { export(listOf(scope.copy(id = " "))) }
        export(listOf(scope))
        assertFailsWith<InputFailure> { export(listOf(scope)) }
        Files.createDirectory(temporary.resolve("occupied.json.assets"))
        assertFailsWith<InputFailure> { StaticInputSnapshots.write(repository, temporary.resolve("occupied.json"), listOf(scope)) }
    }

    @Test fun `optional compiler evidence is immutable and outside source roots remain unattributed`() {
        val repository = repository()
        val classes = Files.createDirectory(temporary.resolve("classes"))
        Files.write(classes.resolve("Library.class"), bytecode("Library"))
        val evidence = Files.writeString(temporary.resolve("compiler.json"), "{}")
        val roots = listOf(repository.resolve("src"), temporary.resolve("outside"))
        roots.forEach { Files.createDirectories(it) }
        val archive = archive("Library")
        val scope = ArchiveScopeInput("library", archive, sourceRoots = roots,
            sourceOutputs = roots.associateWith { listOf(classes) },
            declaredSources = roots.map { DeclaredSourceRoot("library", "main", it) },
            compilerEvidence = listOf(CompilerEvidence(classes, evidence)),
            sourceClasspaths = roots.associateWith { listOf(archive) },
            projectArtifacts = mapOf(archive to listOf(classes)), javaVersion = 17)
        val manifest = StaticInputSnapshots.write(repository, temporary.resolve("mapped/input.json"), listOf(scope))
        val read = readSnapshot(manifest).scopes.single()
        assertFalse(read.compilerEvidence.single().classes == classes)
        assertEquals("{}", Files.readString(read.compilerEvidence.single().manifest))
        val pair = StaticInputSnapshots.prepare(manifest, manifest, repository, repository, temporary.resolve("prepared"))
        assertTrue(pair.first.scopes.values.single().sourceRoots.none { it == roots.last() })
    }

    @Test fun `changed snapshot contents unsupported formats and escaped assets are rejected`() {
        val repository = repository()
        val manifest = write(repository, "checked")
        val record = readSnapshot(manifest)
        fun store(value: SnapshotRecord) = snapshotMapper.writeValue(manifest.toFile(), value)
        listOf("../escape.jar", temporary.resolve("absolute.jar").toString()).forEach { escaped ->
            store(record.copy(hashes = record.hashes + (escaped to "untrusted")))
            assertFailsWith<InputFailure> { readSnapshot(manifest) }
        }
        store(record.copy(version = 2))
        assertFailsWith<InputFailure> { readSnapshot(manifest) }
        store(record.copy(assetDirectory = temporary.resolve("outside-assets")))
        assertFailsWith<InputFailure> { readSnapshot(manifest) }
        store(record.copy(assetDirectory = record.storedDirectory.resolve("missing")))
        assertFailsWith<InputFailure> { readSnapshot(manifest) }
        store(record.copy(scopes = record.scopes.map { it.copy(archive = temporary.resolve("outside.jar")) }))
        assertFailsWith<InputFailure> { readSnapshot(manifest) }
        store(record.copy(scopes = record.scopes.map { it.copy(archive = record.storedDirectory.resolve("../escape.jar")) }))
        assertFailsWith<InputFailure> { readSnapshot(manifest) }
        store(record)
        Files.writeString(record.scopes.single().archive, "corrupt")
        assertFailsWith<InputFailure> { readSnapshot(manifest) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "blank", "duplicate"])
    fun `tampered snapshot scope identities fail before static input preparation`(mode: String) {
        val repository = repository()
        val manifest = write(repository, "scope-identities")
        val record = readSnapshot(manifest)
        val scope = record.scopes.single()
        val scopes = when (mode) {
            "empty" -> emptyList()
            "blank" -> listOf(scope.copy(id = " "))
            else -> listOf(scope, scope)
        }
        snapshotMapper.writeValue(manifest.toFile(), record.copy(scopes = scopes))
        val output = temporary.resolve("not-prepared")
        val failure = assertFailsWith<InputFailure> {
            StaticInputSnapshots.prepare(manifest, manifest, repository, repository, output)
        }
        assertTrue(failure.message.contains("scope identit"))
        assertFalse(Files.exists(output))
    }

    @Test fun `overlapping asset sources are reused and absent optional outputs remain absent`() {
        val assets = SnapshotAssets(temporary, temporary.resolve("assets"))
        val source = Files.createDirectory(temporary.resolve("source"))
        val nested = Files.writeString(source.resolve("file"), "content")
        val copied = assets.copy(nested)
        assets.copy(source)
        assertEquals(copied, assets.copy(nested))
        val directoryFirst = SnapshotAssets(temporary, temporary.resolve("other-assets"))
        directoryFirst.copy(source)
        assertEquals("content", Files.readString(directoryFirst.copy(nested)))
        assertEquals("content", Files.readString(copied))
        assertFalse(Files.exists(assets.copy(temporary.resolve("absent"))))
        assertTrue(assets.hashes().isNotEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["added", "changed", "modified", "removed", "missing", "conflicting"])
    fun `snapshots reject tracked content that differs from their recorded commit`(state: String) {
        val repository = repository()
        Git.open(repository.toFile()).use { git ->
            when (state) {
                "added" -> { Files.writeString(repository.resolve("new"), "new"); git.add().addFilepattern("new").call() }
                "changed" -> { Files.writeString(repository.resolve("README"), "edit"); git.add().addFilepattern("README").call() }
                "modified" -> Files.writeString(repository.resolve("README"), "edit")
                "removed" -> git.rm().addFilepattern("README").call()
                "missing" -> Files.delete(repository.resolve("README"))
                "conflicting" -> {
                    val branch = git.repository.branch
                    git.checkout().setCreateBranch(true).setName("other").call()
                    Files.writeString(repository.resolve("README"), "other"); val other = commit(repository)
                    git.checkout().setName(branch).call()
                    Files.writeString(repository.resolve("README"), "main"); commit(repository)
                    git.merge().include(other).call()
                }
            }
        }
        assertFailsWith<InputFailure> { write(repository, "dirty") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Foo.java", "src/main/java/Foo.java"])
    fun `compiled untracked sources cannot claim the committed revision`(sourcePath: String) {
        val repository = repository()
        val source = repository.resolve(sourcePath)
        Files.createDirectories(source.parent)
        Files.writeString(source, "public class Foo { public static int value() { return 1; } }")
        val classes = Files.createDirectory(temporary.resolve("compiled"))
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        val artifact = temporary.resolve("compiled.jar")
        JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
            jar.putNextEntry(JarEntry("Foo.class")); jar.write(Files.readAllBytes(classes.resolve("Foo.class"))); jar.closeEntry()
        }
        val output = temporary.resolve("not-exported")
        val failure = assertFailsWith<InputFailure> {
            StaticInputSnapshots.write(repository, output.resolve("input.json"),
                listOf(ArchiveScopeInput("library", artifact, javaVersion = 17)))
        }
        assertTrue(failure.message.contains("untracked"))
        assertFalse(Files.exists(output))
    }

    @Test fun `ignored build products and snapshot output do not make the source checkout dirty`() {
        val repository = repository()
        Files.writeString(repository.resolve(".gitignore"), "build/\n")
        val revision = commit(repository).name
        val build = Files.createDirectory(repository.resolve("build"))
        val artifact = Files.copy(archive("Library"), build.resolve("library.jar"))
        val manifest = StaticInputSnapshots.write(repository, build.resolve("snapshot/input.json"),
            listOf(ArchiveScopeInput("library", artifact, javaVersion = 17)))
        assertEquals(revision, readSnapshot(manifest).sha)
        Git.open(repository.toFile()).use { git -> assertTrue(git.status().call().isClean) }
    }

    @Test fun `unborn checkout cannot export or impersonate a saved revision`() {
        val repository = repository()
        val manifest = write(repository, "base")
        val unborn = Files.createDirectory(temporary.resolve("unborn"))
        Git.init().setDirectory(unborn.toFile()).call().close()
        assertFailsWith<IllegalArgumentException> { write(unborn, "unborn-export") }
        assertFailsWith<InputFailure> {
            StaticInputSnapshots.prepare(manifest, manifest, unborn, repository, temporary.resolve("out"))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `flat archive distinguishes owned project dependencies from external types`(symlink: Boolean) {
        val repository = repository()
        val own = archive("Own")
        val project = archive("ProjectDependency")
        val producer = if (symlink) Files.createSymbolicLink(temporary.resolve("project-alias.jar"), project) else project
        val external = archive("External")
        val scope = ArchiveScopeInput("flat", own, dependencies = listOf(project, external),
            projectArtifacts = mapOf(producer to emptyList()), javaVersion = 17)
        val manifest = StaticInputSnapshots.write(repository, temporary.resolve("flat/input.json"), listOf(scope))
        val recorded = readSnapshot(manifest).scopes.single()
        assertEquals(setOf(recorded.dependencies.first()), recorded.projectArtifacts.keys)
        val prepared = StaticInputSnapshots.prepare(manifest, manifest, repository, repository, temporary.resolve("out"))
        val classes = prepared.first.scopes.values.single().classes
        assertTrue(Files.exists(classes.resolve("Own.class")))
        assertTrue(Files.exists(classes.resolve("ProjectDependency.class")))
        assertFalse(Files.exists(classes.resolve("External.class")))
    }

    private fun write(repository: Path, name: String): Path = StaticInputSnapshots.write(repository,
        temporary.resolve("$name/input.json"), listOf(ArchiveScopeInput("library", archive(name), javaVersion = 17)))

    private fun repository(): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        Files.writeString(root.resolve("README"), "committed")
        Git.init().setDirectory(root.toFile()).call().close()
        commit(root)
        return root
    }

    private fun commit(root: Path) = Git.open(root.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("snapshot").setAuthor("Test", "test@example.invalid")
            .setCommitter("Test", "test@example.invalid").call()
    }

    private fun archive(name: String): Path = temporary.resolve("$name.jar").also { path ->
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("$name.class")); jar.write(bytecode(name)); jar.closeEntry()
        }
    }

    private fun bytecode(name: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()
}
