package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AnalysisInputsTest {
    @TempDir lateinit var directory: Path

    @Test fun `directory dependencies retain separate class and resource namespaces`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val first = Files.createDirectory(directory.resolve("first"))
        val second = Files.createDirectory(directory.resolve("second"))
        write(application, "shared/Type.class", "application")
        write(first, "shared/Type.class", "first")
        write(second, "shared/Type.class", "second")
        write(first, "messages.properties", "message=first")
        write(second, "messages.properties", "message=second")

        val stage = AnalysisInputs(application, listOf(first, second)).use { inputs ->
            assertTrue(Files.isSymbolicLink(inputs.path.resolve("shared/Type.class")))
            assertEquals("application", Files.readString(inputs.path.resolve("shared/Type.class")))
            assertFalse(Files.exists(inputs.path.resolve("messages.properties")))
            listOf("first", "second").forEachIndexed { index, expected ->
                val archive = inputs.path.resolve("dependency-$index.jar")
                assertFalse(Files.isSymbolicLink(archive))
                ZipFile(archive.toFile()).use { zip ->
                    assertEquals(expected, contents(zip, "shared/Type.class"))
                    assertEquals("message=$expected", contents(zip, "messages.properties"))
                }
            }
            inputs.path
        }
        assertFalse(Files.exists(stage))
        assertEquals("first", Files.readString(first.resolve("shared/Type.class")))
        assertEquals("second", Files.readString(second.resolve("shared/Type.class")))
    }

    @Test fun `directory archives are deterministic and include resources with portable paths`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val dependency = Files.createDirectory(directory.resolve("dependency"))
        write(dependency, "z/Type.class", "class")
        write(dependency, "a/config.properties", "key=value")
        val before = AnalysisInputs(application, listOf(dependency)).use { inputs ->
            val archive = inputs.path.resolve("dependency-0.jar")
            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(listOf("a/config.properties", "z/Type.class"), entries.map { it.name })
                assertTrue(entries.all { it.time == 0L })
                assertEquals("key=value", contents(zip, "a/config.properties"))
            }
            Files.readAllBytes(archive)
        }
        Files.setLastModifiedTime(dependency.resolve("z/Type.class"), FileTime.fromMillis(0))
        val after = AnalysisInputs(application, listOf(dependency)).use { inputs ->
            Files.readAllBytes(inputs.path.resolve("dependency-0.jar"))
        }
        assertContentEquals(before, after)
    }

    @Test fun `empty directory dependencies remain distinct valid archives`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val dependency = Files.createDirectory(directory.resolve("dependency"))
        AnalysisInputs(application, listOf(dependency)).use { inputs ->
            ZipFile(inputs.path.resolve("dependency-0.jar").toFile()).use { zip -> assertEquals(0, zip.size()) }
        }
        assertTrue(Files.exists(dependency))
    }

    @Test fun `a directory symlink retains the target dependency contents`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val dependency = Files.createDirectory(directory.resolve("dependency"))
        write(dependency, "library/Type.class", "dependency")
        val alias = Files.createSymbolicLink(directory.resolve("alias"), dependency)
        AnalysisInputs(application, listOf(alias)).use { inputs ->
            ZipFile(inputs.path.resolve("dependency-0.jar").toFile()).use { zip ->
                assertEquals("dependency", contents(zip, "library/Type.class"))
            }
        }
        assertTrue(Files.isSymbolicLink(alias))
        assertEquals("dependency", Files.readString(dependency.resolve("library/Type.class")))
    }

    @Test fun `lexical archive discovery preserves more than ten supplied dependencies`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val labels = ('a'..'l').map(Char::toString)
        val dependencies = labels.map { label ->
            Files.createDirectory(directory.resolve(label)).also { write(it, "value.txt", label) }
        }
        AnalysisInputs(application, dependencies).use { inputs ->
            val values = Files.list(inputs.path).use { files ->
                files.sorted().map { archive ->
                    ZipFile(archive.toFile()).use { zip -> contents(zip, "value.txt") }
                }.toList()
            }
            assertEquals(labels, values)
            assertTrue(Files.exists(inputs.path.resolve("dependency-00.jar")))
            assertTrue(Files.exists(inputs.path.resolve("dependency-11.jar")))
        }
    }

    @Test fun `embedded resources cannot become staged dependencies or overwrite the originals`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val dependency = Files.createDirectory(directory.resolve("dependency"))
        write(application, "dependency-0.jar", "application-owned content")
        write(dependency, "library/Type.class", "dependency")
        AnalysisInputs(application, listOf(dependency)).use { inputs ->
            ZipFile(inputs.path.resolve("dependency-0.jar").toFile()).use { zip ->
                assertEquals("dependency", contents(zip, "library/Type.class"))
            }
        }
        assertEquals("application-owned content", Files.readString(application.resolve("dependency-0.jar")))
        assertEquals("dependency", Files.readString(dependency.resolve("library/Type.class")))
    }

    @Test fun `staged directory archives are cleaned when the consumer fails`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val dependency = Files.createDirectory(directory.resolve("dependency"))
        val inputs = AnalysisInputs(application, listOf(dependency))
        assertFailsWith<IllegalStateException> {
            inputs.use {
                assertTrue(Files.exists(it.path.resolve("dependency-0.jar")))
                error("consumer failure")
            }
        }
        assertFalse(Files.exists(inputs.path))
        assertTrue(Files.exists(dependency))
    }

    @Test fun `class only application directories do not require staging`() {
        val application = Files.createDirectory(directory.resolve("classes"))
        write(application, "Type.class", "prepared class")
        AnalysisInputs(application, emptyList()).use { inputs -> assertEquals(application, inputs.path) }
        assertTrue(Files.isRegularFile(application.resolve("Type.class")))
    }

    private fun write(root: Path, path: String, content: String) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun contents(zip: ZipFile, name: String): String =
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes().toString(Charsets.UTF_8) }
}
