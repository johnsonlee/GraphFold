package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MavenReactorTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `only effective reactor modules are traversed including explicit POM paths`() {
        val root = pom("pom.xml", "<project/>")
        val child = pom("child/custom.xml", "<project/>")
        pom("src/it/fixture/pom.xml", "broken")
        val effective = pom("effective.xml", """
            <project><modules><module>child/custom.xml</module></modules>
              <profiles><profile><modules><module>inactive</module></modules></profile></profiles></project>
        """.trimIndent())
        val models = MavenReactor(temporary).models { path, index ->
            assertEquals(if (path == root) 0 else 1, index)
            if (path == root) effective else child
        }
        assertEquals(listOf(root, child), models.keys.toList())
    }

    @Test
    fun `directory modules and no modules are supported`() {
        val root = pom("pom.xml", "<project><modules><module>child</module></modules></project>")
        val child = pom("child/pom.xml", "<project/>")
        assertEquals(listOf(root, child), MavenReactor(temporary).models { path, _ -> path }.keys.toList())
    }

    @Test
    fun `missing cyclic outside and custom source modules fail before compilation`() {
        val root = pom("pom.xml", "<project/>")
        listOf("missing", ".", "../outside.xml").forEach { module ->
            Files.writeString(root, "<project><modules><module>$module</module></modules></project>")
            assertFailsWith<BuildFailure> { MavenReactor(temporary).models { path, _ -> path } }
        }
        Files.writeString(root, "<project><build><sourceDirectory>custom</sourceDirectory></build></project>")
        assertFailsWith<BuildFailure> { MavenReactor(temporary).models { _, _ -> error("must not export") } }
    }

    @Test
    fun `existing module outside checkout is rejected`() {
        val checkout = Files.createDirectory(temporary.resolve("checkout"))
        pom("outside.xml", "<project/>")
        pom("checkout/pom.xml", "<project><modules><module>../outside.xml</module></modules></project>")
        assertFailsWith<BuildFailure> { MavenReactor(checkout).models { path, _ -> path } }
    }

    private fun pom(path: String, contents: String): Path = temporary.resolve(path).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, contents)
    }
}
