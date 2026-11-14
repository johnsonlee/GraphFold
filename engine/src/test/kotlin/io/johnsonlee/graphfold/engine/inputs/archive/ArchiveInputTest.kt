package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveInputTest {
    @TempDir lateinit var temporary: Path

    @Test fun `packaged bytes win over compiler outputs and excluded classes never enter graph`() {
        val compiled = provenance()
        val transformed = bytecode("sample/Main", 9)
        val jar = archive("application.jar", mapOf("sample/Main.class" to transformed,
            "settings.properties" to "enabled=true".toByteArray()))
        val result = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(jar, listOf(compiled.classes)))), compiled, temporary.resolve("prepared"))
        assertContentEquals(transformed, Files.readAllBytes(result.classes.resolve("sample/Main.class")))
        assertFalse(Files.exists(result.classes.resolve("sample/Excluded.class")))
        assertEquals(mapOf(temporary.resolve("src").toRealPath() to listOf(compiled.classes.toRealPath())), result.sourceOutputs)
        assertEquals(compiled.originDirectory, result.originDirectory)
        assertEquals(listOf(CompilerEvidence(compiled.classes.toRealPath(), temporary.resolve("own.json"))), result.compilerEvidence)
        JarFile(result.dependencies.single().toFile()).use { projected ->
            assertEquals(null, projected.getJarEntry("sample/Main.class"))
            assertTrue(projected.getJarEntry("settings.properties") != null)
        }
        assertEquals(result.dependencies, result.production!!.resourceDependencies)
        assertEquals(mapOf("enabled" to "true"), result.production.resourceEntries["settings.properties"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `explicit archive order selects different packaged definitions without ownership reordering`(externalFirst: Boolean) {
        val compiled = provenance()
        val external = archive("external.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 7)))
        val app = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1)))
        val entries = listOf(ArchiveEntryInput(app, listOf(compiled.classes)), ArchiveEntryInput(external))
        val result = prepareArchiveInput(ArchiveInput("app", if (externalFirst) entries.reversed() else entries), compiled,
            temporary.resolve("prepared"))
        assertContentEquals(bytecode("sample/Main", if (externalFirst) 7 else 1),
            Files.readAllBytes(result.classes.resolve("sample/Main.class")))
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `identical definitions preserve application ownership with or without source mapping`(externalFirst: Boolean, mapped: Boolean) {
        val compiled = provenance()
        val bytes = bytecode("sample/Main", 1)
        val app = archive("app.jar", mapOf("sample/Main.class" to bytes))
        val external = archive("external.jar", mapOf("sample/Main.class" to bytes))
        val entries = listOf(ArchiveEntryInput(app, if (mapped) listOf(compiled.classes) else emptyList(), application = true),
            ArchiveEntryInput(external))
        val result = prepareArchiveInput(ArchiveInput("app", if (externalFirst) entries.reversed() else entries), compiled,
            temporary.resolve("prepared"))
        assertContentEquals(bytes, Files.readAllBytes(result.classes.resolve("sample/Main.class")))
        assertEquals(if (mapped) listOf(temporary.resolve("src").toRealPath()) else emptyList(), result.sourceRoots)
        result.dependencies.forEach { path -> JarFile(path.toFile()).use { assertEquals(null, it.getJarEntry("sample/Main.class")) } }
    }

    @Test fun `multi release application alternatives use explicit static variant selection`() {
        val compiled = provenance()
        val selected = bytecode("sample/Main", 17)
        val jar = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1),
            "META-INF/versions/17/sample/Main.class" to selected), "Multi-Release: true\r\n")
        val result = prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(jar, listOf(compiled.classes)))),
            compiled, temporary.resolve("prepared"), variantThreshold = 17)
        assertContentEquals(selected, Files.readAllBytes(result.classes.resolve("sample/Main.class")))
    }

    @Test fun `manifest Class-Path closure expands transitively in archive order without following cycles twice`() {
        val app = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1)),
            "Class-Path: first.jar last.jar\r\n")
        val first = archive("first.jar", emptyMap(), "Class-Path: middle.jar\r\n")
        val middle = archive("middle.jar", emptyMap(), "Class-Path: app.jar\r\n")
        val last = archive("last.jar", emptyMap())
        assertEquals(listOf(app, first, middle, last).map { it.toRealPath() }, manifestApplicationClasspath(app))
        val compiled = provenance()
        val prepared = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(app, listOf(compiled.classes)), ArchiveEntryInput(first),
            ArchiveEntryInput(middle), ArchiveEntryInput(last))), compiled, temporary.resolve("prepared"))
        prepared.dependencies.forEach { path -> JarFile(path.toFile()).use {
            assertEquals(null, it.manifest.mainAttributes.getValue("Class-Path"))
        } }
        JarFile(app.toFile()).use { assertEquals("first.jar last.jar", it.manifest.mainAttributes.getValue("Class-Path")) }
    }

    @Test fun `conflicting packaged properties remain outside resource folding proof`() {
        val app = archive("app.jar", mapOf("settings.properties" to "enabled=true".toByteArray()))
        val library = archive("library.jar", mapOf("settings.properties" to "enabled=false".toByteArray()))
        val compiled = provenance()
        val result = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(app, listOf(compiled.classes)), ArchiveEntryInput(library))), compiled,
            temporary.resolve("prepared"))
        assertTrue(result.production!!.resourceEntries.isEmpty())
    }

    @Test fun `an inventory envelope preceding the application never becomes graph bytecode`() {
        val compiled = provenance()
        val envelope = archive("envelope.jar", mapOf("sample/Envelope.class" to bytecode("sample/Envelope", 4),
            "web.properties" to "theme=light".toByteArray()))
        val app = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1)))
        val prepared = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(envelope, inventoryOnly = true), ArchiveEntryInput(app, application = true))),
            compiled, temporary.resolve("prepared"))
        assertContentEquals(bytecode("sample/Main", 1), Files.readAllBytes(prepared.classes.resolve("sample/Main.class")))
        assertFalse(Files.exists(prepared.classes.resolve("sample/Envelope.class")))
        prepared.dependencies.forEach { path -> JarFile(path.toFile()).use {
            assertEquals(null, it.getJarEntry("sample/Envelope.class"))
        } }
        assertEquals(mapOf("theme" to "light"), prepared.production!!.resourceEntries["web.properties"])
    }

    @Test fun `unexpanded loader list cannot claim packaged scope`() {
        val compiled = provenance()
        val app = archive("app.jar", emptyMap(), "Class-Path: dependency.jar\r\n")
        archive("dependency.jar", emptyMap())
        assertTrue(assertFailsWith<InputFailure> {
            prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(app))), compiled,
                temporary.resolve("prepared"))
        }.message.contains("expanded manifest classpath"))
    }

    @Test fun `selected archives do not require main metadata while unresolved classpath inputs fail`() {
        val ordinary = archive("ordinary.jar", emptyMap())
        assertEquals(listOf(ordinary.toRealPath()), manifestApplicationClasspath(ordinary))
        val missing = archive("missing-main.jar", emptyMap(), "Main-Class: sample.Missing\r\n")
        assertEquals(listOf(missing.toRealPath()), manifestApplicationClasspath(missing))
        val remote = archive("remote.jar", emptyMap(), "Class-Path: https://example.invalid/library.jar\r\n")
        assertFailsWith<InputFailure> { manifestApplicationClasspath(remote) }
        assertFailsWith<InputFailure> { manifestApplicationClasspath(temporary.resolve("absent.jar")) }
        assertFailsWith<InputFailure> {
            prepareArchiveInput(ArchiveInput("empty", emptyList()), provenance(), temporary.resolve("empty"))
        }
    }

    @Test fun `a launcher class name does not determine the selected archive layout`() {
        val main = "org.springframework.boot.loader.launch.JarLauncher"
        val jar = archive("flat.jar", mapOf(main.replace('.', '/') + ".class" to bytecode(main.replace('.', '/'), 1)),
            "Main-Class: $main\r\n")
        assertEquals(listOf(jar.toRealPath()), manifestApplicationClasspath(jar))
        val compiled = provenance()
        val result = prepareArchiveInput(
            ArchiveInput("flat", listOf(ArchiveEntryInput(jar, listOf(compiled.classes)))),
            compiled, temporary.resolve("flat"))
        assertTrue(Files.exists(result.classes.resolve(main.replace('.', '/') + ".class")))
    }

    @Test fun `resource projection never canonicalizes escaping or aliased archive entry names`() {
        val compiled = provenance()
        listOf("../outside.properties", "alias/../settings.properties", "").forEachIndexed { index, entry ->
            val jar = archive("invalid-path-$index.jar", mapOf(entry to "value=true".toByteArray()))
            assertTrue(assertFailsWith<InputFailure> {
                prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(jar))), compiled,
                    temporary.resolve("invalid-projection-$index"))
            }.message.contains("Unsupported packaged resource path"))
        }
        assertFalse(Files.exists(temporary.resolve("outside.properties")))
    }

    @Test fun `archive layout metadata remains a production input change with identical packaged classes`() {
        val compiled = provenance()
        val jar = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1),
            "sample/Other.class" to bytecode("sample/Other", 1)))
        val selected = ArchiveInput("app", listOf(ArchiveEntryInput(jar, listOf(compiled.classes))),
            mapOf("package-layout" to "flat"))
        val baseline = prepareArchiveInput(selected, compiled, temporary.resolve("baseline")).production!!
        val reordered = prepareArchiveInput(selected.copy(archiveMetadata = mapOf("package-layout" to "nested")),
            compiled, temporary.resolve("reordered")).production!!
        assertFalse(baseline.entries == reordered.entries)
        assertFalse(baseline.nonClassEntries == reordered.nonClassEntries)
        assertFalse(baseline.resourceIndependentEntries == reordered.resourceIndependentEntries)
        assertTrue(baseline.entries.keys.none { it.contains("mainClass") }, "Launch metadata is not a separate static input")
    }

    @Test fun `ordinary packaged directories and resources do not become application classes`() {
        val compiled = provenance()
        val jar = archive("directories.jar", mapOf("sample/" to byteArrayOf(),
            "sample/Main.class" to bytecode("sample/Main", 1), "sample/readme.txt" to "resource".toByteArray()))
        val prepared = prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(jar))),
            compiled, temporary.resolve("directory-projection"))
        assertTrue(Files.isRegularFile(prepared.classes.resolve("sample/Main.class")))
        assertFalse(Files.exists(prepared.classes.resolve("sample/readme.txt")))
    }

    @Test fun `application class extraction rejects escaped and noncanonical archive paths`() {
        val compiled = provenance()
        listOf("../Main", "alias/../Main").forEachIndexed { index, owner ->
            val jar = archive("class-path-$index.jar", mapOf("$owner.class" to bytecode(owner, 1)))
            assertTrue(assertFailsWith<InputFailure> {
                prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(jar,
                    listOf(compiled.classes)))), compiled, temporary.resolve("class-path-$index"))
            }.message.contains("Unsupported packaged class path"))
        }
    }

    @Test fun `archives without manifests or with blank launch attributes expand to themselves`() {
        val absent = temporary.resolve("no-manifest.jar")
        ZipOutputStream(Files.newOutputStream(absent)).use { it.finish() }
        assertEquals(listOf(absent.toRealPath()), manifestApplicationClasspath(absent))
        val blank = archive("blank-main.jar", emptyMap(), "Main-Class: \r\n")
        assertEquals(listOf(blank.toRealPath()), manifestApplicationClasspath(blank))
    }

    @Test fun `optional uncreated source directories are not passed to source parsers`() {
        val compiled = provenance()
        val absent = temporary.resolve("src/main/kotlin")
        val evidence = compiled.copy(sourceOutputs = compiled.sourceOutputs + (absent to listOf(compiled.classes)))
        val jar = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1)))
        val result = prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(jar,
            listOf(compiled.classes)))), evidence, temporary.resolve("prepared"))
        assertEquals(listOf(temporary.resolve("src").toRealPath()), result.sourceRoots)
        assertTrue(absent.toFile().canonicalFile.toPath() in result.sourceOutputs)
    }

    @Test fun `agent launch metadata does not gate static archive inputs`() {
        val jar = archive("agent-app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1)),
            "Main-Class: sample.Main\r\nLauncher-Agent-Class: sample.Agent\r\n")
        assertEquals(listOf(jar.toRealPath()), manifestApplicationClasspath(jar))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `physical compiler identities merge aliases but conflicting contexts cannot be silently overwritten`(ambiguous: Boolean) {
        val compiled = provenance()
        val source = temporary.resolve("src")
        val sourceAlias = Files.createSymbolicLink(temporary.resolve("source-alias"), source)
        val classesAlias = Files.createSymbolicLink(temporary.resolve("classes-alias"), compiled.classes)
        val dependency = Files.createDirectory(temporary.resolve("dependency"))
        val dependencyAlias = Files.createSymbolicLink(temporary.resolve("dependency-alias"), dependency)
        val other = Files.createDirectory(temporary.resolve("other-dependency"))
        val evidence = compiled.copy(sourceOutputs = compiled.sourceOutputs + (sourceAlias to listOf(classesAlias)),
            sourceClasspaths = mapOf(source to listOf(dependency),
                sourceAlias to listOf(if (ambiguous) other else dependencyAlias), temporary.resolve("unrelated") to listOf(other)))
        val jar = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1)))
        val selected = ArchiveInput("app", listOf(ArchiveEntryInput(jar, listOf(classesAlias))))
        if (ambiguous) {
            val result = prepareArchiveInput(selected, evidence, temporary.resolve("prepared"))
            assertTrue(Files.exists(result.classes.resolve("sample/Main.class")))
            assertTrue(result.provenanceIssues.any { it.contains("Ambiguous compiler classpaths") })
        } else {
            val result = prepareArchiveInput(selected, evidence, temporary.resolve("prepared"))
            assertEquals(mapOf(source.toRealPath() to listOf(compiled.classes.toRealPath())), result.sourceOutputs)
            assertEquals(mapOf(source.toRealPath() to listOf(dependency.toRealPath())), result.sourceClasspaths)
        }
    }

    private fun provenance(): InputProject {
        val classes = Files.createDirectories(temporary.resolve("compiled"))
        Files.createDirectories(classes.resolve("sample"))
        Files.write(classes.resolve("sample/Main.class"), bytecode("sample/Main", 1))
        Files.write(classes.resolve("sample/Excluded.class"), bytecode("sample/Excluded", 3))
        val source = Files.createDirectories(temporary.resolve("src"))
        val unrelated = Files.createDirectories(temporary.resolve("unrelated"))
        return InputProject(classes, listOf(source, unrelated), emptyList(), temporary.resolve("origins"),
            sourceOutputs = mapOf(source to listOf(classes), unrelated to listOf(temporary.resolve("other-classes"))),
            compilerEvidence = listOf(CompilerEvidence(classes, temporary.resolve("own.json")),
                CompilerEvidence(temporary.resolve("other-classes"), temporary.resolve("unrelated-invalid.json"))))
    }

    private fun archive(
        name: String, entries: Map<String, ByteArray>, attributes: String = "",
    ): Path = temporary.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val files = mapOf(JarFile.MANIFEST_NAME to ("Manifest-Version: 1.0\r\n" + attributes + "\r\n").toByteArray()) + entries
            files.forEach { (entry, bytes) ->
                zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun bytecode(owner: String, value: Int): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
