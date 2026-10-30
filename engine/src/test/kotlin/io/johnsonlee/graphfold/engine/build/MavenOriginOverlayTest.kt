package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenOriginOverlayTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `effective compiler arguments and annotation processor paths survive temporary overlay`() {
        val pom = temporary.resolve("pom.xml")
        val original = "<project><!-- keep exact bytes --><modelVersion>4.0.0</modelVersion></project>".toByteArray()
        Files.write(pom, original)
        val model = effective("""
            <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration>
              <compilerArgs><arg>-parameters</arg><arg>-Xlint:unchecked</arg></compilerArgs>
              <annotationProcessorPaths><path><groupId>fixture</groupId><artifactId>processor</artifactId>
                <version>1</version></path></annotationProcessorPaths>
            </configuration><executions><execution><id>default-compile</id><configuration>
              <compilerArgs><arg>-Xlint:deprecation</arg></compilerArgs>
            </configuration></execution></executions></plugin>
        """.trimIndent())
        overlay(mapOf(pom to model)).use {
            val text = Files.readString(pom)
            assertTrue("-parameters" in text && "-Xlint:unchecked" in text && "-Xlint:deprecation" in text, text)
            assertTrue("<artifactId>processor</artifactId>" in text, text)
            assertTrue("<artifactId>compiler-origin-provider</artifactId>" in text, text)
            assertTrue("-Xplugin:GraphFoldOrigins" in text, text)
            assertTrue("combine.self=\"override\"" in text, text)
            assertTrue("<scope>provided</scope>" in text, text)
            assertTrue("<optional>true</optional>" in text, text)
            assertFalse("useIncrementalCompilation" in text, text)
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
        }
        assertContentEquals(original, Files.readAllBytes(pom))
    }

    @Test
    fun `default processor discovery is preserved and Kotlin arguments append without dropping originals`() {
        val pom = temporary.resolve("pom.xml")
        Files.writeString(pom, "<project/>")
        val model = effective("""
            <plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId>
            <version>2.1.20</version><configuration><args><arg>-Xjsr305=strict</arg></args></configuration></plugin>
        """.trimIndent())
        overlay(mapOf(pom to model)).use {
            val text = Files.readString(pom)
            assertFalse("annotationProcessorPaths" in text, text)
            assertTrue("-Xjsr305=strict" in text && "-Xplugin=" in text, text)
            assertTrue("plugin:io.johnsonlee.graphfold.origin.kotlin:runId=test-run" in text, text)
            assertTrue("plugin:io.johnsonlee.graphfold.origin.kotlin:output=" in text, text)
        }
        assertEquals("<project/>", Files.readString(pom))
    }

    @Test
    fun `unsupported compiler backend restores all original files when overlay setup fails`() {
        val first = temporary.resolve("pom.xml")
        val second = Files.createDirectory(temporary.resolve("module")).resolve("pom.xml")
        Files.writeString(first, "<project><!-- first --></project>")
        Files.writeString(second, "<project><!-- second --></project>")
        val firstModel = effective("")
        val secondModel = temporary.resolve("effective-other.xml")
        Files.writeString(secondModel, """
            <project><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
            <configuration><compilerId>eclipse</compilerId></configuration></plugin></plugins></build></project>
        """.trimIndent())
        assertFailsWith<BuildFailure> { overlay(linkedMapOf(first to firstModel, second to secondModel)) }
        assertEquals("<project><!-- first --></project>", Files.readString(first))
        assertEquals("<project><!-- second --></project>", Files.readString(second))
    }

    private fun effective(plugins: String): Path = temporary.resolve("effective.xml").also {
        Files.writeString(it, "<project><build><plugins>$plugins</plugins></build></project>")
    }

    private fun overlay(models: Map<Path, Path>): MavenOriginOverlay {
        val output = Files.createDirectory(temporary.resolve("output"))
        val provider = Files.write(output.resolve("provider.jar"), byteArrayOf(1, 2, 3))
        return MavenOriginOverlay(temporary, output, provider, models, "test-run")
    }
}
