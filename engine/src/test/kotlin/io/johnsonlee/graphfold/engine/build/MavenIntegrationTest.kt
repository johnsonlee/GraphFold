package io.johnsonlee.graphfold.engine.build

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises a real reactor and Maven's dependency export, including an external artifact. */
class MavenIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `real Maven reactor exports project classes and external dependencies`() {
        val maven = downloadMaven()
        val project = Files.createDirectory(temporary.resolve("project"))
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\nexec sh '${maven.resolve("bin/mvn")}' \"${'$'}@\"\n")
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId>
              <version>1</version><packaging>pom</packaging>
              <properties><maven.compiler.source>17</maven.compiler.source><maven.compiler.target>17</maven.compiler.target></properties>
              <modules><module>library</module><module>app</module></modules>
              <dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                <version>1.18.36</version><scope>provided</scope></dependency></dependencies>
              <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
                <configuration><compilerArgs><arg>-parameters</arg></compilerArgs><annotationProcessorPaths>
                  <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version></path>
                </annotationProcessorPaths></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        module(project, "library", "",
            "package example; public class Library { @lombok.Getter private Object callback = new Object() {}; }")
        module(project, "app", """
            <dependencies>
              <dependency><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version></dependency>
              <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version></dependency>
            </dependencies>
        """.trimIndent(), """
            package example;
            public class App extends Library {
              public boolean empty(String value) { return org.apache.commons.lang3.StringUtils.isEmpty(value); }
            }
        """.trimIndent())
        val originalPoms = listOf("pom.xml", "library/pom.xml", "app/pom.xml").associateWith {
            Files.readString(project.resolve(it))
        }
        val result = ProjectCompiler(Duration.ofMinutes(3)).compile(project, temporary.resolve("output"))
        assertTrue(Files.exists(result.classes.resolve("example/App.class")))
        assertTrue(Files.exists(result.classes.resolve("example/Library.class")))
        val libraryClass = org.objectweb.asm.tree.ClassNode().also {
            org.objectweb.asm.ClassReader(Files.readAllBytes(result.classes.resolve("example/Library.class"))).accept(it, 0)
        }
        assertTrue(libraryClass.methods.any { it.name == "getCallback" }, "Existing Lombok processor must still execute")
        assertEquals(2, result.sourceRoots.size)
        assertTrue(result.dependencies.any { it.fileName.toString() == "commons-lang3-3.17.0.jar" })
        assertTrue(result.dependencies.none { it.startsWith(project) })
        assertTrue(result.dependencies.none { it.fileName.toString().startsWith("compiler-origin-provider-") })
        originalPoms.forEach { (path, text) -> assertEquals(text, Files.readString(project.resolve(path))) }
        val origins = Files.list(requireNotNull(result.originDirectory)).use { paths ->
            paths.filter { it.toString().endsWith(".json") }.map {
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(it.toFile())
            }.toList()
        }
        assertTrue(origins.all { it["runId"].asText() == result.originRunId })
        assertTrue(origins.flatMap { it["records"].toList() }.any { it["owner"].asText() == "example.Library${'$'}1" })
    }

    @Test
    fun `real Maven Kotlin compilation emits object provenance without changing the project POM`() {
        val maven = downloadMaven()
        val project = Files.createDirectory(temporary.resolve("kotlin-project"))
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\nexec sh '${maven.resolve("bin/mvn")}' \"${'$'}@\"\n")
        val pom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>kotlin-app</artifactId><version>1</version>
              <dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId>
                <version>2.1.20</version></dependency></dependencies>
              <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId>
                <version>2.1.20</version><configuration><jvmTarget>17</jvmTarget><args><arg>-Xjsr305=strict</arg></args>
                <sourceDirs><sourceDir>${'$'}{project.basedir}/src/main/kotlin</sourceDir></sourceDirs></configuration>
                <executions><execution><id>compile</id><phase>compile</phase><goals><goal>compile</goal></goals></execution></executions>
              </plugin></plugins></build>
            </project>
        """.trimIndent()
        Files.writeString(project.resolve("pom.xml"), pom)
        val source = Files.createDirectories(project.resolve("src/main/kotlin/example"))
        Files.writeString(source.resolve("Example.kt"), "package example; class Example { fun value(): Any = object {} }")
        val result = ProjectCompiler(Duration.ofMinutes(3)).compile(project, temporary.resolve("kotlin-output"))
        assertTrue(Files.exists(result.classes.resolve("example/Example.class")))
        assertEquals(pom, Files.readString(project.resolve("pom.xml")))
        val records = Files.list(requireNotNull(result.originDirectory)).use { files ->
            files.filter { it.toString().endsWith(".json") }.map {
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(it.toFile())
            }.toList()
        }
        assertTrue(records.all { it["runId"].asText() == result.originRunId })
        assertTrue(records.flatMap { it["records"].toList() }.any {
            it["owner"].asText().startsWith("example.Example${'$'}")
        }, records.toString())
        assertTrue(result.dependencies.none { it.fileName.toString().startsWith("compiler-origin-provider-") })
    }

    private fun module(project: Path, name: String, dependencies: String, source: String) {
        val root = Files.createDirectories(project.resolve("$name/src/main/java/example"))
        val className = if (name == "library") "Library" else "App"
        Files.writeString(root.resolve("$className.java"), source)
        Files.writeString(project.resolve("$name/pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent>
              <artifactId>$name</artifactId>
              $dependencies
            </project>
        """.trimIndent())
    }

    private fun downloadMaven(): Path {
        val url = URI("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip").toURL()
        val bytes = url.openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }.getInputStream().use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(
            "03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05" +
                "b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076", digest,
        )
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = temporary.resolve(entry.name).normalize()
                check(target.startsWith(temporary))
                if (entry.isDirectory) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target)
                }
                entry = zip.nextEntry
            }
        }
        return temporary.resolve("apache-maven-3.9.11")
    }
}
