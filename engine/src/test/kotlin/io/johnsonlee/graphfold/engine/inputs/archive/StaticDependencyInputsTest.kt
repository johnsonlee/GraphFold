package io.johnsonlee.graphfold.engine.inputs.archive

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.ServiceLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StaticDependencyInputsTest {
    @TempDir lateinit var temporary: Path

    @Test fun `physical archive alternatives are selected only by an explicit static variant`() {
        val original = archive(mapOf("sample/Value.class" to bytecode("sample/Value", 1),
            "META-INF/versions/9/sample/Value.class" to bytecode("sample/Value", 2),
            "META-INF/versions/99/sample/Value.class" to bytecode("sample/Value", 3)))
        val before = Files.readAllBytes(original)
        for ((threshold, value) in listOf(0 to 1, 9 to 2, 99 to 3)) {
            val projected = normalizeDependencyInputs(listOf(original), temporary.resolve("variant-$threshold"),
                variantThreshold = threshold).single()
            JarFile(projected.toFile()).use { jar ->
                assertContentEquals(bytecode("sample/Value", value), jar.getInputStream(jar.getJarEntry("sample/Value.class")).readBytes())
            }
        }
        assertContentEquals(before, Files.readAllBytes(original))
    }

    @Test fun `ordinary archives and directories retain identity and order`() {
        val jar = archive(mapOf("sample/Value.class" to bytecode("sample/Value", 1)), manifest = null)
        val directory = Files.createDirectory(temporary.resolve("classes"))
        Files.createDirectories(directory.resolve("sample"))
        Files.write(directory.resolve("sample/Other.class"), bytecode("sample/Other", 2))
        assertEquals(listOf(jar, directory), normalizeDependencyInputs(listOf(jar, directory), temporary.resolve("out")))
    }

    @Test fun `different definitions are ambiguous independent of archive order alone`() {
        val first = archive(mapOf("sample/Value.class" to bytecode("sample/Value", 1)), manifest = null, name = "first.jar")
        val second = archive(mapOf("sample/Value.class" to bytecode("sample/Value", 2)), manifest = null, name = "second.jar")
        for ((index, inputs) in listOf(listOf(first, second), listOf(second, first)).withIndex()) {
            val failure = assertFailsWith<InputFailure> {
                normalizeDependencyInputs(inputs, temporary.resolve("out-$index"))
            }
            assertTrue(failure.message.contains("Ambiguous static class definition: sample/Value.class"))
        }
    }

    @Test fun `identical copies deduplicate but different directory definitions are ambiguous`() {
        val directory = Files.createDirectories(temporary.resolve("classes/sample"))
        val bytes = bytecode("sample/Value", 1)
        Files.write(directory.resolve("Value.class"), bytes)
        val jar = archive(mapOf("sample/Value.class" to bytes), manifest = null)
        val selected = normalizeDependencyInputs(listOf(jar, directory.parent), temporary.resolve("same"))
        JarFile(selected.last().toFile()).use { assertFalse(it.entries().hasMoreElements()) }
        Files.write(directory.resolve("Value.class"), bytecode("sample/Value", 2))
        assertFailsWith<InputFailure> {
            normalizeDependencyInputs(listOf(jar, directory.parent), temporary.resolve("different"))
        }
    }

    @Test fun `non multi release archives cannot activate versioned classes`() {
        val base = bytecode("sample/Value", 1)
        val jar = archive(mapOf("sample/Value.class" to base,
            "META-INF/versions/9/sample/Value.class" to bytecode("sample/Value", 2)), manifest = null)
        JarFile(project(jar).toFile()).use { effective ->
            assertContentEquals(base, effective.getInputStream(effective.getJarEntry("sample/Value.class")).readBytes())
            assertEquals(listOf("sample/Value.class"), effective.entries().asSequence().map { it.name }.toList())
        }
    }

    @Test fun `empty multi release marker is removed from the static projection`() {
        val jar = archive(mapOf("sample/Value.class" to bytecode("sample/Value", 1)))
        JarFile(project(jar).toFile()).use { assertFalse(it.isMultiRelease) }
    }

    @Test fun `directory classpaths never activate multi release layout and omit validated module metadata`() {
        val directory = Files.createDirectory(temporary.resolve("classes"))
        val base = bytecode("sample/Value", 1)
        mapOf("sample/Value.class" to base, "module-info.class" to module(),
            "META-INF/versions/9/sample/Value.class" to bytecode("sample/Value", 2),
            "value.txt" to "value".toByteArray(), JarFile.MANIFEST_NAME to "Manifest-Version: 1.0\r\n\r\n".toByteArray())
            .forEach { (name, bytes) ->
            Files.createDirectories(directory.resolve(name).parent)
            Files.write(directory.resolve(name), bytes)
        }
        JarFile(project(directory).toFile()).use { effective ->
            assertEquals(setOf("sample/Value.class", "value.txt", JarFile.MANIFEST_NAME),
                effective.entries().asSequence().map { it.name }.toSet())
            assertContentEquals(base, effective.getInputStream(effective.getJarEntry("sample/Value.class")).readBytes())
        }
    }

    @Test fun `JVM multi release lookup properties cannot select an alternate static input`() {
        val archive = archive(mapOf("sample/Value.class" to bytecode("sample/Value", 1),
            "META-INF/versions/99/sample/Value.class" to bytecode("sample/Value", 2)))
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        for (option in listOf("-Djdk.util.jar.version=9", "-Djdk.util.jar.enableMultiRelease=false")) {
            val process = ProcessBuilder(javaExecutable, option, "-cp", System.getProperty("graphfold.test.runtimeClasspath"),
                StaticDependencyInputsTest::class.java.name, archive.toString()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytecode("sample/Value", 2))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, output.trim())
        }
    }

    @Test fun `missing dependencies remain failures`() {
        assertFailsWith<InputFailure> { project(temporary.resolve("missing.jar")) }
    }

    @Test fun `all valid module descriptors are metadata across explicit archive variants`() {
        val jar = archive(mapOf("module-info.class" to module(provider = true),
            "META-INF/versions/9/module-info.class" to module(),
            "META-INF/versions/99/module-info.class" to module(provider = true)))
        for (variant in listOf(0, 9, 99)) {
            val projected = normalizeDependencyInputs(listOf(jar), temporary.resolve("metadata-$variant"),
                variantThreshold = variant).single()
            JarFile(projected.toFile()).use { archive ->
                assertFalse(archive.entries().asSequence().any { it.name.endsWith("module-info.class") })
            }
        }
    }

    @Test fun `static classpath retains service resources and validates module metadata`() {
        val classes = mapOf("module-info.class" to module(provider = true),
            "sample/Service.class" to serviceInterface(), "sample/Provider.class" to serviceProvider())
        listOf(false, true).forEach { registered ->
            val entries = if (registered) classes + ("META-INF/services/sample.Service" to "sample.Provider\n".toByteArray())
                else classes
            val original = archive(entries, name = "services-$registered.jar")
            val projected = normalizeDependencyInputs(listOf(original), temporary.resolve("services-$registered")).single()
            assertServices(original, registered)
            assertServices(projected, registered)
        }
    }

    @Test fun `module filename cannot erase executable class bytes`() {
        val jar = archive(mapOf("module-info.class" to bytecode("sample/Value", 1)))
        assertFailsWith<InputFailure> { project(jar) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["nested.jar", "nested.zip", "nested.war", "nested.ear", "nested.JAR",
        "BOOT-INF/classes/sample/Hidden.class", "WEB-INF/classes/sample/Hidden.class"])
    fun `static classpath excludes nested resource archives from graph inputs`(name: String) {
        val payload = if (name.endsWith(".class")) bytecode("sample/Hidden", 99) else byteArrayOf(1, 2)
        val jar = archive(mapOf("module-info.class" to module(), "sample/Value.class" to bytecode("sample/Value", 1),
            name to payload), manifest = null)
        val projected = normalizeDependencyInputs(listOf(jar), temporary.resolve("ordinary")).single()
        JarFile(projected.toFile()).use {
            assertEquals(null, it.getJarEntry(name))
            assertContentEquals(bytecode("sample/Value", 1), it.getInputStream(it.getJarEntry("sample/Value.class")).readBytes())
        }
        JarFile(jar.toFile()).use { assertContentEquals(payload, it.getInputStream(it.getJarEntry(name)).readBytes()) }
        val directory = Files.createDirectory(temporary.resolve("directory"))
        val opaque = directory.resolve(name)
        Files.createDirectories(opaque.parent)
        Files.write(opaque, payload)
        val output = normalizeDependencyInputs(listOf(directory), temporary.resolve("directory-output")).single()
        JarFile(output.toFile()).use { assertEquals(null, it.getJarEntry(name)) }
        assertContentEquals(payload, Files.readAllBytes(opaque))
    }

    @Test fun `implicit manifest classpath remains a blocker`() {
        val jar = archive(mapOf("module-info.class" to module()),
            "Manifest-Version: 1.0\r\nClass-Path: other.jar\r\n\r\n")
        assertFailsWith<InputFailure> { project(jar) }
        val empty = archive(mapOf("module-info.class" to module()),
            "Manifest-Version: 1.0\r\nClass-Path: \r\n\r\n")
        assertNotEquals(empty, project(empty))
    }

    @Test fun `class identity must match but readable newer and preview bytecode is not gated by host JVM`() {
        val mismatched = archive(mapOf("sample/Value.class" to bytecode("wrong/Value", 1)), name = "mismatched.jar")
        assertFailsWith<InputFailure> {
            normalizeDependencyInputs(listOf(mismatched), temporary.resolve("mismatched"))
        }
        listOf(Opcodes.V21, Opcodes.V_PREVIEW or Opcodes.V21).forEachIndexed { index, version ->
            val bytes = bytecode("sample/Value", 1, version)
            val jar = archive(mapOf("sample/Value.class" to bytes), manifest = null, name = "readable-$index.jar")
            assertEquals(listOf(jar), normalizeDependencyInputs(listOf(jar), temporary.resolve("readable-$index")))
        }
    }

    @Test fun `archive and directory class identities must match their static input paths`() {
        val bytes = bytecode("different/Identity", 1)
        val jar = archive(mapOf("sample/Value.class" to bytes), manifest = null)
        val directory = Files.createDirectories(temporary.resolve("classes/sample"))
        Files.write(directory.resolve("Value.class"), bytes)
        for ((index, input) in listOf(jar, directory.parent).withIndex()) {
            val failure = assertFailsWith<InputFailure> {
                normalizeDependencyInputs(listOf(input), temporary.resolve("mismatched-$index"))
            }
            assertTrue(failure.message.contains("Static class identity differs from archive path"))
        }
    }

    @Test fun `unsupported ASM major versions have build input diagnostics`() {
        val ordinary = archive(mapOf("sample/Value.class" to bytecode("sample/Value", 1, version = 999)),
            manifest = null, name = "future-class.jar")
        val descriptor = module().apply { this[6] = 3; this[7] = 231.toByte() }
        val modular = archive(mapOf("module-info.class" to descriptor), name = "future-module.jar")
        listOf(ordinary, modular).forEachIndexed { index, jar ->
            val failure = assertFailsWith<InputFailure> {
                normalizeDependencyInputs(listOf(jar), temporary.resolve("future-$index"))
            }
            assertTrue(failure.message.contains("Unsupported input bytecode"))
            assertTrue(failure.message.contains("Unsupported class file major version 999"))
        }
    }

    @Test fun `duplicate zip entries are rejected before selection`() {
        val jar = archive(mapOf("a.txt" to byteArrayOf(1), "b.txt" to byteArrayOf(2)))
        val bytes = Files.readAllBytes(jar)
        val old = "b.txt".toByteArray()
        (0..bytes.size - old.size).filter { offset -> old.indices.all { bytes[offset + it] == old[it] } }
            .forEach { bytes[it] = 'a'.code.toByte() }
        Files.write(jar, bytes)
        assertTrue(assertFailsWith<InputFailure> { project(jar) }.message.contains("Duplicate"))
    }

    private fun project(path: Path): Path = normalizeDependencyInputs(listOf(path), temporary.resolve("out")).single()

    private fun archive(
        entries: Map<String, ByteArray>, manifest: String? = "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n",
        name: String = "input.jar",
    ): Path = temporary.resolve(name).also { file ->
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            val contents = if (manifest == null) entries else mapOf(JarFile.MANIFEST_NAME to manifest.toByteArray()) + entries
            contents.forEach { (entry, bytes) -> zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry() }
        }
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            val output = Files.createTempDirectory("static-variant-")
            try {
                val projected = normalizeDependencyInputs(listOf(Path.of(args.single())), output, variantThreshold = 99).single()
                val bytes = JarFile(projected.toFile()).use { jar ->
                    jar.getInputStream(jar.getJarEntry("sample/Value.class")).readBytes()
                }
                println(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            } finally {
                Files.walk(output).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}

class StaticApplicationInputsTest {
    @TempDir lateinit var temporary: Path

    @Test fun `proven application copies are omitted while project jar service resources remain`() {
        val classes = Files.createDirectories(temporary.resolve("classes/sample"))
        val bytes = bytecode("sample/Value", 1)
        Files.write(classes.resolve("Value.class"), bytes)
        val jar = temporary.resolve("project.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("sample/Value.class")); zip.write(bytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/services/sample.Service")); zip.write("sample.Value".toByteArray()); zip.closeEntry()
        }
        val projected = normalizeDependencyInputs(listOf(jar), temporary.resolve("out"),
            applicationClasses = classes.parent).single()
        JarFile(projected.toFile()).use { archive ->
            assertEquals(listOf("META-INF/services/sample.Service"), archive.entries().asSequence().map { it.name }.toList())
            val resource = archive.getJarEntry("META-INF/services/sample.Service")
            assertEquals("sample.Value", archive.getInputStream(resource).bufferedReader().readText())
        }
        val duplicateDirectory = normalizeDependencyInputs(listOf(classes.parent), temporary.resolve("directory-out"),
            applicationClasses = classes.parent).single()
        JarFile(duplicateDirectory.toFile()).use { assertFalse(it.entries().hasMoreElements()) }
    }

    @Test fun `different application definition cannot be silently discarded without full lookup ownership`() {
        val classes = Files.createDirectories(temporary.resolve("classes/sample"))
        Files.write(classes.resolve("Value.class"), bytecode("sample/Value", 1))
        val jar = temporary.resolve("dependency.jar")
        val dependency = bytecode("sample/Value", 2)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("sample/Value.class")); zip.write(dependency); zip.closeEntry()
        }
        val failure = assertFailsWith<InputFailure> {
            normalizeDependencyInputs(listOf(jar), temporary.resolve("out"),
                applicationClasses = classes.parent)
        }
        assertTrue(failure.message.contains("Ambiguous application/dependency class definition"))
    }

    @Test fun `application copy lookup cannot escape its root or hide a dependency only class`() {
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val escaped = bytecode("../Escaped", 1)
        Files.write(temporary.resolve("Escaped.class"), escaped)
        val jar = temporary.resolve("dependency.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("../Escaped.class")); zip.write(escaped); zip.closeEntry()
            zip.putNextEntry(ZipEntry("sample/Only.class")); zip.write(bytecode("sample/Only", 2)); zip.closeEntry()
        }
        assertEquals(listOf(jar), normalizeDependencyInputs(listOf(jar), temporary.resolve("out"),
            applicationClasses = classes))
    }
}

class StaticModuleDescriptorTest {
    @TempDir lateinit var temporary: Path

    @Test fun `module metadata never excuses executable members or invalid descriptor structure`() {
        InvalidInputModule.entries.forEach { invalid ->
            val jar = temporary.resolve("$invalid.jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
                zip.putNextEntry(ZipEntry("module-info.class")); zip.write(invalidModule(invalid)); zip.closeEntry()
            }
            assertFailsWith<InputFailure>(invalid.name) {
                normalizeDependencyInputs(listOf(jar), temporary.resolve(invalid.name))
            }
        }
    }
}

private enum class InvalidInputModule { ACCESS, VERSION, MISSING_MODULE, METHOD, FIELD, SUPER, INTERFACE }

private fun invalidModule(invalid: InvalidInputModule): ByteArray = ClassWriter(0).apply {
    visit(if (invalid == InvalidInputModule.VERSION) Opcodes.V1_8 else Opcodes.V9,
        if (invalid == InvalidInputModule.ACCESS) Opcodes.ACC_PUBLIC else Opcodes.ACC_MODULE,
        "module-info", null, if (invalid == InvalidInputModule.SUPER) "java/lang/Object" else null,
        if (invalid == InvalidInputModule.INTERFACE) arrayOf("java/lang/Runnable") else null)
    if (invalid != InvalidInputModule.MISSING_MODULE) visitModule("sample.library", 0, null).visitEnd()
    if (invalid == InvalidInputModule.METHOD) visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).visitEnd()
    if (invalid == InvalidInputModule.FIELD) visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd()
    visitEnd()
}.toByteArray()

private fun module(provider: Boolean = false): ByteArray = ClassWriter(0).apply {
    visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
    visitModule("sample.library", 0, null).apply {
        visitRequire("java.base", Opcodes.ACC_MANDATED, null)
        if (provider) visitProvide("sample/Service", "sample/Provider")
        visitEnd()
    }
    visitEnd()
}.toByteArray()

private fun bytecode(owner: String, value: Int, version: Int = Opcodes.V1_8): ByteArray = ClassWriter(0).apply {
    visit(version, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
    visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
        visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
    }
    visitEnd()
}.toByteArray()

private fun serviceInterface(): ByteArray = ClassWriter(0).apply {
    visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
        "sample/Service", null, "java/lang/Object", null)
    visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value", "()I", null, null).visitEnd()
    visitEnd()
}.toByteArray()

private fun serviceProvider(): ByteArray = ClassWriter(0).apply {
    visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Provider", null, "java/lang/Object", arrayOf("sample/Service"))
    visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
        visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
        visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
    }
    visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
        visitCode(); visitLdcInsn(7); visitInsn(Opcodes.IRETURN); visitMaxs(1, 1); visitEnd()
    }
    visitEnd()
}.toByteArray()

private fun assertServices(jar: Path, registered: Boolean) {
    URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
        val service = loader.loadClass("sample.Service")
        val providers = ServiceLoader.load(service, loader).iterator()
        assertEquals(registered, providers.hasNext())
        if (registered) assertEquals(7, service.getMethod("value").invoke(providers.next()))
    }
}
