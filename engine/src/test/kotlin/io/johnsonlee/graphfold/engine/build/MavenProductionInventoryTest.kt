package io.johnsonlee.graphfold.engine.build

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenProductionInventoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `processed resources runtime-only dependencies and actual package contents are captured`() {
        val fixture = fixture("app", finalName = "production")
        Files.writeString(fixture.classes.resolve("message.txt"), "runtime translation")
        val runtime = archive(temporary.resolve("runtime.jar"), mapOf("runtime.txt" to "runtime-only"))
        val provider = temporary.resolve("provider.jar")
        Files.writeString(fixture.manifest, listOf(runtime, provider, fixture.artifact).joinToString(File.pathSeparator))
        val inventory = assertNotNull(fixture.inventory.capture(fixture.classes, temporary.resolve("out")) { it == provider })
        assertTrue(inventory.nonClassEntries.containsKey("resource/0/message.txt"))
        assertTrue(inventory.nonClassEntries.containsKey("dependency/0"))
        assertTrue(inventory.entries.keys.any { it.startsWith("dependency/1") })
        assertFalse(inventory.entries.keys.any { it.startsWith("dependency/2") })
        assertTrue(inventory.nonClassEntries.containsKey("artifact/0/packaged.txt"))
        assertFalse(inventory.nonClassEntries.containsKey("artifact/0/App.class"))
        Files.writeString(fixture.classes.resolve("message.txt"), "changed translation")
        val changed = fixture.inventory.capture(fixture.classes, temporary.resolve("changed")) { it == provider }
        assertNotEquals(inventory, changed)
    }

    @Test
    fun `analysis POM metadata is normalized without changing incremental package outputs`() {
        val base = fixture("base")
        val head = fixture("head")
        val baseBytes = Files.readAllBytes(base.artifact)
        val headBytes = Files.readAllBytes(head.artifact)
        val before = base.inventory.capture(base.classes, temporary.resolve("out-base")) { false }
        val after = head.inventory.capture(head.classes, temporary.resolve("out-head")) { false }
        assertEquals(before, after)
        assertTrue(baseBytes.contentEquals(Files.readAllBytes(base.artifact)))
        assertTrue(headBytes.contentEquals(Files.readAllBytes(head.artifact)))
        val modified = fixture("changed", originalPom = "<project><name>new published metadata</name></project>")
        assertNotEquals(before, modified.inventory.capture(modified.classes, temporary.resolve("out-changed")) { false })
    }

    @Test
    fun `runtime dependency content changes are observable`() {
        val fixture = fixture("app")
        val runtime = archive(temporary.resolve("dependency.jar"), mapOf("version.txt" to "one"))
        Files.writeString(fixture.manifest, runtime.toString())
        val first = fixture.inventory.capture(fixture.classes, temporary.resolve("first")) { false }
        archive(runtime, mapOf("version.txt" to "two"))
        val second = fixture.inventory.capture(fixture.classes, temporary.resolve("second")) { false }
        assertNotEquals(first, second)
    }

    @Test
    fun `reactor runtime scopes preserve group boundaries order and repeated dependencies`() {
        val root = fixture("reactor")
        val child = fixture("reactor/child")
        val one = archive(temporary.resolve("one.jar"), mapOf("one" to "one"))
        val two = archive(temporary.resolve("two.jar"), mapOf("two" to "two"))
        val exporter = MavenProductionInventory(linkedMapOf(root.pom to root.effective, child.pom to child.effective))
        Files.writeString(root.manifest, one.toString())
        Files.writeString(child.manifest, two.toString())
        val separated = exporter.capture(root.classes, temporary.resolve("separated")) { false }
        Files.writeString(root.manifest, listOf(one, two).joinToString(File.pathSeparator))
        Files.writeString(child.manifest, "")
        val combined = exporter.capture(root.classes, temporary.resolve("combined")) { false }
        assertNotEquals(separated, combined)
        Files.writeString(root.manifest, listOf(two, one).joinToString(File.pathSeparator))
        assertNotEquals(combined, exporter.capture(root.classes, temporary.resolve("reordered")) { false })
        Files.writeString(root.manifest, listOf(one, two, two).joinToString(File.pathSeparator))
        assertNotEquals(combined, exporter.capture(root.classes, temporary.resolve("repeated")) { false })
    }

    @Test
    fun `missing manifest and declared package fail closed and stale manifests are removed`() {
        val fixture = fixture("app")
        fixture.inventory.prepare(temporary.resolve("state"))
        assertFalse(Files.exists(fixture.manifest))
        fixture.inventory.prepare(temporary.resolve("state"))
        assertFailsWith<BuildFailure> { fixture.inventory.capture(fixture.classes, temporary.resolve("out")) { false } }
        Files.writeString(fixture.manifest, "")
        Files.delete(fixture.artifact)
        assertFailsWith<BuildFailure> { fixture.inventory.capture(fixture.classes, temporary.resolve("out2")) { false } }
    }

    @Test
    fun `effective model omitting default jar packaging retains the main jar`() {
        val fixture = fixture("app", mutate = { it.replace("<packaging>jar</packaging>", "") })
        val inventory = assertNotNull(fixture.inventory.capture(fixture.classes, temporary.resolve("out")) { false })
        assertTrue(inventory.entries.containsKey("artifact/0"))
    }

    @Test
    fun `aggregator POM modules do not require a synthetic jar`() {
        val fixture = fixture("parent", packaging = "pom")
        Files.delete(fixture.artifact)
        val inventory = assertNotNull(fixture.inventory.capture(fixture.classes, temporary.resolve("out")) { false })
        assertFalse(inventory.entries.keys.any { it.startsWith("artifact/") })
    }

    @Test
    fun `attached test and sources jars are excluded from primary production artifacts`() {
        val fixture = fixture("app", plugins = """
            <plugin><artifactId>maven-jar-plugin</artifactId><executions><execution><goals><goal>test-jar</goal></goals>
            <configuration><classifier>tests</classifier></configuration></execution></executions></plugin>
            <plugin><artifactId>maven-source-plugin</artifactId></plugin>
            <plugin><artifactId>maven-javadoc-plugin</artifactId></plugin>
        """.trimIndent())
        archive(fixture.artifact.resolveSibling("app-tests.jar"), mapOf("Test.class" to "test only"))
        archive(fixture.artifact.resolveSibling("app-sources.jar"), mapOf("App.java" to "source only"))
        val inventory = assertNotNull(fixture.inventory.capture(fixture.classes, temporary.resolve("out")) { false })
        assertFalse(inventory.entries.keys.any { it.startsWith("artifact/1") })
    }

    @Test
    fun `unsupported packaging layouts plugins and artifact overrides leave an explicit gap`() {
        val cases = listOf<(String) -> String>(
            { it.replace("<packaging>jar</packaging>", "<packaging>war</packaging>") },
            { it.replace("<build>", "<unused>").replace("</build>", "</unused>") },
            { it.replace("<directory>", "<unused>").replace("</directory>", "</unused>") },
            { it.replace("<outputDirectory>", "<unused>").replace("</outputDirectory>", "</unused>") },
            { it.replace("<finalName>app</finalName>", "<finalName></finalName>") },
            { it.replace("<finalName>app</finalName>", "<finalName>../app</finalName>") },
            { it.replace("<finalName>app</finalName>", "<finalName>..\\app</finalName>") },
            { it.replace("<finalName>app</finalName>", "<finalName>\${unresolved}</finalName>") },
            { it.replace("<groupId>fixture</groupId>", "") },
            { it.replace("<artifactId>app</artifactId>", "") },
            { it.replace("<plugins/>", "<plugins><plugin><artifactId>maven-assembly-plugin</artifactId></plugin></plugins>") },
            { it.replace("<plugins/>", """
                <plugins><plugin><artifactId>maven-jar-plugin</artifactId>
                <configuration><classifier>runtime</classifier></configuration></plugin></plugins>
            """.trimIndent()) },
            { it.replace("<plugins/>", """
                <plugins><plugin><artifactId>maven-jar-plugin</artifactId><executions><execution>
                <goals><goal>jar</goal></goals><configuration><finalName>custom</finalName></configuration>
                </execution></executions></plugin></plugins>
            """.trimIndent()) },
        )
        cases.forEachIndexed { index, mutate ->
            val fixture = fixture("case-$index", mutate = mutate)
            assertFalse(fixture.inventory.supported, "Case $index")
            fixture.inventory.prepare(temporary.resolve("state"))
            assertNull(fixture.inventory.capture(fixture.classes, temporary.resolve("out-$index")) { false })
        }
    }

    @Test
    fun `ordinary jar configuration and explicit default execution remain supported`() {
        val fixture = fixture("app", plugins = """
            <plugin><artifactId>maven-jar-plugin</artifactId><configuration><archive><manifest/></archive></configuration>
            <executions><execution><goals><goal>jar</goal></goals><configuration><archive/></configuration></execution>
            <execution><id>unused</id></execution></executions></plugin>
        """.trimIndent())
        assertTrue(fixture.inventory.supported)
    }

    @Test
    fun `effective filtering resource roots publish ownership without touching compiler outputs`() {
        val source = Files.createDirectories(temporary.resolve("inputs"))
        Files.writeString(source.resolve("messages.properties"), "message=\${version}")
        val fixture = fixture("app", plugins = """
            <plugin><artifactId>maven-resources-plugin</artifactId><configuration><encoding>UTF-8</encoding></configuration>
            <executions><execution><goals><goal>resources</goal></goals></execution></executions></plugin>
        """.trimIndent(), mutate = { xml -> xml.replace("<build>", """
            <build><resources><resource><directory>$source</directory><filtering>true</filtering></resource></resources>
        """.trimIndent()) })
        Files.writeString(fixture.classes.resolve("messages.properties"), "message=1")
        fixture.inventory.prepare(temporary.resolve("state"))
        Files.writeString(fixture.manifest, "")
        assertNotNull(fixture.inventory.capture(fixture.classes, temporary.resolve("out")) { false })
        assertTrue(Files.readString(temporary.resolve("state/production-resources.json")).contains("messages.properties"))
    }

    @Test
    fun `unsupported resource mappings are unavailable instead of guessed`() {
        val source = temporary.resolve("inputs")
        val mappings = listOf(
            "<directory>$source</directory><includes><include>**/*.txt</include></includes>",
            "<directory>$source</directory><excludes><exclude>ignored.txt</exclude></excludes>",
            "<directory>$source</directory><targetPath>nested</targetPath>",
            "<directory></directory>", "<directory>relative</directory>", "<directory>\${unresolved}</directory>",
        )
        mappings.forEachIndexed { index, mapping ->
            val fixture = fixture("resource-$index", mutate = { xml ->
                xml.replace("<build>", "<build><resources><resource>$mapping</resource></resources>")
            })
            assertFalse(fixture.inventory.supported)
        }
        listOf(
            "<configuration><outputDirectory>custom</outputDirectory></configuration>",
            "<executions><execution><goals><goal>copy-resources</goal></goals></execution></executions>",
        ).forEachIndexed { index, configuration ->
            val fixture = fixture("plugin-$index", plugins =
                "<plugin><artifactId>maven-resources-plugin</artifactId>$configuration</plugin>")
            assertFalse(fixture.inventory.supported)
        }
    }

    @Test
    fun `runtime dependency export has isolated scope and restored original POM`() {
        val fixture = fixture("app", plugins = "<plugin><artifactId>maven-dependency-plugin</artifactId><version>3.8.1</version></plugin>")
        val original = Files.readString(fixture.pom)
        val provider = Files.write(temporary.resolve("provider.jar"), byteArrayOf(1))
        MavenOriginOverlay(fixture.pom.parent, temporary.resolve("state"), provider,
            mapOf(fixture.pom to fixture.effective), "test", true).use { overlay ->
            assertFalse(overlay.isProviderDependency(temporary.resolve("unrelated.jar")))
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(fixture.pom.toFile())
            val execution = (0 until document.getElementsByTagName("execution").length)
                .map { document.getElementsByTagName("execution").item(it) }
                .first { it.textContent.contains("graphfold-production-runtime") }
            assertTrue(execution.textContent.contains("runtime"))
            assertTrue(execution.textContent.contains(fixture.manifest.toString()))
            assertTrue(Files.readString(fixture.pom).contains("combine.self=\"override\""))
        }
        assertEquals(original, Files.readString(fixture.pom))
    }

    @Test
    fun `reserved runtime export execution cannot be silently replaced`() {
        val original = """
            <project><build><plugins><plugin><artifactId>maven-dependency-plugin</artifactId><executions>
            <execution><id>graphfold-production-runtime</id></execution></executions></plugin></plugins></build></project>
        """.trimIndent()
        val fixture = fixture("app", originalPom = original)
        val provider = Files.write(temporary.resolve("provider.jar"), byteArrayOf(1))
        assertFailsWith<BuildFailure> {
            MavenOriginOverlay(fixture.pom.parent, temporary.resolve("state"), provider,
                mapOf(fixture.pom to fixture.effective), "test", true)
        }
        assertEquals(original, Files.readString(fixture.pom))
    }

    private fun fixture(
        name: String, packaging: String = "jar", finalName: String = "app", originalPom: String = "<project/>",
        plugins: String = "", mutate: (String) -> String = { it },
    ): Fixture {
        val module = Files.createDirectories(temporary.resolve(name))
        val classes = Files.createDirectories(module.resolve("target/classes"))
        Files.writeString(classes.resolve("App.class"), "application")
        val pom = Files.writeString(module.resolve("pom.xml"), originalPom)
        val effective = Files.writeString(module.resolve("effective.xml"), mutate("""
            <project><groupId>fixture</groupId><artifactId>app</artifactId><packaging>$packaging</packaging>
            <build><directory>${module.resolve("target")}</directory><outputDirectory>$classes</outputDirectory>
            <finalName>$finalName</finalName>${if (plugins.isEmpty()) "<plugins/>" else "<plugins>$plugins</plugins>"}</build></project>
        """.trimIndent()))
        val artifact = archive(module.resolve("target/$finalName.jar"), mapOf(
            "META-INF/" to "", "META-INF/maven/fixture/app/pom.xml" to "injected analysis POM at $module",
            "App.class" to "application", "packaged.txt" to "content",
        ))
        val manifest = Files.writeString(module.resolve("target/graphfold-runtime-classpath.txt"), "")
        return Fixture(pom, effective, classes, manifest, artifact, MavenProductionInventory(mapOf(pom to effective)))
    }

    private fun archive(path: Path, entries: Map<String, String>): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return path
    }

    private data class Fixture(
        val pom: Path, val effective: Path, val classes: Path, val manifest: Path, val artifact: Path,
        val inventory: MavenProductionInventory,
    )
}
