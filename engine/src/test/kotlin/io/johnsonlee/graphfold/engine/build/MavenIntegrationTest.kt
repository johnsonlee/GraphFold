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
              <profiles><profile><id>retired-module</id><activation><activeByDefault>true</activeByDefault></activation>
                <modules><module>retired</module></modules></profile></profiles>
              <dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                <version>1.18.36</version><scope>provided</scope></dependency></dependencies>
              <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
                <configuration><compilerArgs><arg>-parameters</arg></compilerArgs><annotationProcessorPaths>
                  <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version></path>
                </annotationProcessorPaths></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        module(project, "library", """
            <build><plugins><plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version>
              <executions><execution><goals><goal>test-jar</goal></goals></execution></executions>
            </plugin></plugins></build>
        """.trimIndent(),
            "package example; public class Library { @lombok.Getter private Object callback = new Object() {}; }")
        module(project, "app", """
            <dependencies>
              <dependency><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version></dependency>
              <dependency><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version>
                <type>test-jar</type><scope>test</scope></dependency>
              <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version></dependency>
            </dependencies>
        """.trimIndent(), """
            package example;
            public class App extends Library {
              public boolean empty(String value) { return org.apache.commons.lang3.StringUtils.isEmpty(value); }
            }
        """.trimIndent())
        module(project, "retired", "", "package example; public class Retired { Object value = new Object() {}; }")
        reactorTestSources(project)
        // Neither unlisted examples nor plugin integration fixture POMs belong to the production reactor.
        listOf("src/it/broken", "src/test/resources/broken", "examples/unlisted").forEach { path ->
            val fixture = Files.createDirectories(project.resolve("$path/src/main/java"))
            Files.writeString(fixture.resolve("Broken.java"), "this is deliberately invalid Java")
            Files.writeString(project.resolve("$path/pom.xml"), "not even XML")
        }
        val originalPoms = listOf("pom.xml", "library/pom.xml", "app/pom.xml", "retired/pom.xml").associateWith {
            Files.readString(project.resolve(it))
        }
        val result = ProjectCompiler(Duration.ofMinutes(3)).compile(project, temporary.resolve("output"))
        assertTrue(Files.exists(result.classes.resolve("example/App.class")))
        assertTrue(Files.exists(result.classes.resolve("example/Library.class")))
        assertTrue(Files.exists(project.resolve("app/target/test-classes/example/ConsumerTest.class")))
        assertTrue(!Files.exists(result.classes.resolve("example/SharedTestSupport.class")))
        val libraryClass = org.objectweb.asm.tree.ClassNode().also {
            org.objectweb.asm.ClassReader(Files.readAllBytes(result.classes.resolve("example/Library.class"))).accept(it, 0)
        }
        assertTrue(libraryClass.methods.any { it.name == "getCallback" }, "Existing Lombok processor must still execute")
        assertEquals(3, result.sourceRoots.size)
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
        verifyJavaReuse(project, result)
    }

    private fun reactorTestSources(project: Path) {
        val libraryTests = Files.createDirectories(project.resolve("library/src/test/java/example"))
        Files.writeString(libraryTests.resolve("SharedTestSupport.java"),
            "package example; public class SharedTestSupport { public static int number() { return 1; } }")
        Files.writeString(libraryTests.resolve("Library.java"),
            "package example; public class Library { Object testCallback = new Object() {}; }")
        val appTests = Files.createDirectories(project.resolve("app/src/test/java/example"))
        Files.writeString(appTests.resolve("ConsumerTest.java"),
            "package example; public class ConsumerTest { int value = SharedTestSupport.number(); }")
    }

    private fun verifyJavaReuse(project: Path, first: CompiledProject) {
        val compiler = ProjectCompiler(Duration.ofMinutes(3))
        val libraryClass = project.resolve("library/target/classes/example/Library.class")
        val timestamp = Files.getLastModifiedTime(libraryClass)
        val unchanged = compiler.compile(project, temporary.resolve("unchanged"))
        assertEquals(first.originRunId, unchanged.originRunId)
        assertEquals(timestamp, Files.getLastModifiedTime(libraryClass))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(unchanged, project).diagnostics.isEmpty())
        val shadow = project.resolve("library/src/test/java/example/Library.java")
        Files.writeString(shadow, Files.readString(shadow).replace("new Object() {}", "new Object() { int value = 2; }"))
        val testEdited = compiler.compile(project, temporary.resolve("test-edited"))
        assertEquals(timestamp, Files.getLastModifiedTime(libraryClass))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(testEdited, project).diagnostics.isEmpty())
        val records = regularFiles(requireNotNull(testEdited.originDirectory)).flatMap {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(it.toFile())["records"].toList()
        }
        val mainOrigin = records.single { it["owner"].asText() == "example.Library${'$'}1" }
        val mainSource = project.resolve("library/src/main/java/example/Library.java")
        assertEquals(mainSource.toRealPath(), Path.of(mainOrigin["source"].asText()).toRealPath())
        assertEquals(digest(mainSource), mainOrigin["sourceHash"].asText())
        assertTrue(records.none { "/src/test/" in it["source"].asText() })
        val app = project.resolve("app/src/main/java/example/App.java")
        Files.writeString(app, Files.readString(app).replace("isEmpty(value)", "isBlank(value)"))
        val edited = compiler.compile(project, temporary.resolve("edited"))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(edited, project).diagnostics.isEmpty())
        Files.delete(project.resolve("retired/src/main/java/example/Retired.java"))
        val deleted = compiler.compile(project, temporary.resolve("deleted"))
        assertTrue(!Files.exists(deleted.classes.resolve("example/Retired.class")))
        assertTrue(!Files.exists(deleted.classes.resolve("example/Retired${'$'}1.class")))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(deleted, project).diagnostics.isEmpty())
        listOf("pom.xml", "library/pom.xml", "app/pom.xml", "retired/pom.xml").forEach { path ->
            val pom = project.resolve(path)
            Files.writeString(pom, Files.readString(pom).replace("<version>1</version>", "<version>2</version>"))
        }
        val bumped = compiler.compile(project, temporary.resolve("version-bump"))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(bumped, project).diagnostics.isEmpty())
        val bumpedTimestamp = Files.getLastModifiedTime(libraryClass)
        compiler.compile(project, temporary.resolve("version-bump-reused"))
        assertEquals(bumpedTimestamp, Files.getLastModifiedTime(libraryClass))
    }

    @Test
    fun `real Maven Kotlin compilation emits object provenance without changing the project POM`() {
        val maven = downloadMaven()
        val project = Files.createDirectory(temporary.resolve("kotlin-project"))
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\nexec sh '${maven.resolve("bin/mvn")}' \"${'$'}@\"\n")
        val pom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>kotlin-app</artifactId><version>1</version>
              <properties><kotlin.compiler.incremental>true</kotlin.compiler.incremental>
                <maven.compiler.source>17</maven.compiler.source><maven.compiler.target>17</maven.compiler.target></properties>
              <dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId>
                <version>2.1.20</version></dependency></dependencies>
              <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId>
                <version>2.1.20</version><configuration><jvmTarget>17</jvmTarget><args><arg>-Xjsr305=strict</arg></args>
                <sourceDirs><sourceDir>${'$'}{project.basedir}/src/main/kotlin</sourceDir></sourceDirs></configuration>
                <executions><execution><id>compile</id><phase>compile</phase><goals><goal>compile</goal></goals></execution></executions>
              </plugin><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build>
            </project>
        """.trimIndent()
        Files.writeString(project.resolve("pom.xml"), pom)
        val source = Files.createDirectories(project.resolve("src/main/kotlin/example"))
        Files.writeString(source.resolve("Example.kt"), "package example; class Example { fun value(): Any = object {} }")
        Files.writeString(source.resolve("Unchanged.kt"), "package example; class Unchanged { fun value(): Any = object {} }")
        Files.writeString(source.resolve("InlineDef.kt"),
            "package example; inline fun inlineValue(crossinline value: () -> Int): Any = object { fun run() = value() }")
        Files.writeString(source.resolve("InlineCaller.kt"), "package example; fun caller(): Any = inlineValue { 1 }")
        val java = Files.createDirectories(project.resolve("src/main/java/example"))
        Files.writeString(java.resolve("Sentinel.java"), "package example; public class Sentinel {}")
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
        val ownership = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .readTree(project.resolve("target/graphfold/maven/outputs.json").toFile())
        assertTrue(ownership.any { record ->
            record["owner"].asText().startsWith("example.InlineCallerKt${'$'}") &&
                record["sources"].any { Path.of(it["path"].asText()).fileName.toString() == "InlineCaller.kt" }
        }, "Inline copy ownership must identify the compiler's caller source: $ownership")
        verifyKotlinReuse(project, source, result)
    }

    private fun verifyKotlinReuse(project: Path, source: Path, first: CompiledProject) {
        val compiler = ProjectCompiler(Duration.ofMinutes(3))
        val binary = project.resolve("target/classes/example/Unchanged.class")
        val timestamp = Files.getLastModifiedTime(binary)
        val unchanged = compiler.compile(project, temporary.resolve("kotlin-unchanged"))
        assertEquals(first.originRunId, unchanged.originRunId)
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(unchanged, project).diagnostics.isEmpty())
        Files.writeString(source.resolve("Example.kt"),
            "package example; class Example { fun value(): Any = object { val number = 2 } }")
        val edited = compiler.compile(project, temporary.resolve("kotlin-edited"))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(edited, project).diagnostics.isEmpty())
        assertEquals(timestamp, Files.getLastModifiedTime(binary))
        val beforeShift = inlineCopy(edited)
        val definition = source.resolve("InlineDef.kt")
        Files.writeString(definition, "\n" + Files.readString(definition))
        val shifted = compiler.compile(project, temporary.resolve("kotlin-inline-shift"))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(shifted, project).diagnostics.isEmpty())
        val afterShift = inlineCopy(shifted)
        assertEquals(digest(definition), afterShift["sourceHash"].asText())
        assertTrue(beforeShift["sourceHash"] != afterShift["sourceHash"])
        assertEquals(beforeShift["allocation"]["start"].asInt() + 1, afterShift["allocation"]["start"].asInt())
        Files.list(source).use { files -> files.forEach(Files::delete) }
        val deleted = compiler.compile(project, temporary.resolve("kotlin-deleted"))
        val owners = regularFiles(deleted.classes).map { deleted.classes.relativize(it).toString() }
        assertEquals(listOf("example/Sentinel.class"), owners)
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(deleted, project).diagnostics.isEmpty())
        val pom = project.resolve("pom.xml")
        Files.writeString(pom, Files.readString(pom).replace("<jvmTarget>17", "<jvmTarget>11"))
        Files.writeString(source.resolve("Rebuilt.kt"), "package example; class Rebuilt { fun value(): Any = object {} }")
        val changed = compiler.compile(project, temporary.resolve("kotlin-compiler-change"))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(changed, project).diagnostics.isEmpty())
        val rebuilt = project.resolve("target/classes/example/Rebuilt.class")
        val classNode = org.objectweb.asm.tree.ClassNode().also {
            org.objectweb.asm.ClassReader(Files.readAllBytes(rebuilt)).accept(it, 0)
        }
        assertEquals(org.objectweb.asm.Opcodes.V11, classNode.version)
        val rebuiltTimestamp = Files.getLastModifiedTime(rebuilt)
        compiler.compile(project, temporary.resolve("kotlin-compiler-change-reused"))
        assertEquals(rebuiltTimestamp, Files.getLastModifiedTime(rebuilt))
    }

    private fun inlineCopy(project: CompiledProject): com.fasterxml.jackson.databind.JsonNode =
        regularFiles(requireNotNull(project.originDirectory)).flatMap {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(it.toFile())["records"].toList()
        }.single { it["owner"].asText().startsWith("example.InlineCallerKt${'$'}") }

    private fun module(project: Path, name: String, dependencies: String, source: String) {
        val root = Files.createDirectories(project.resolve("$name/src/main/java/example"))
        val className = name.replaceFirstChar(Char::uppercase)
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
