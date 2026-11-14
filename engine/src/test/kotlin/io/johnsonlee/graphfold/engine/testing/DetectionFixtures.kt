package io.johnsonlee.graphfold.engine.testing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.johnsonlee.graphfold.engine.DetectionCorpusTest.CorpusCase
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.inputs.archive.InputProject
import io.johnsonlee.graphfold.engine.inputs.archive.DependencyInputCache
import io.johnsonlee.graphfold.engine.inputs.archive.prepareRevision
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

data class DetectionCase(val language: String, val case: CorpusCase) {
    override fun toString(): String = "$language/${case.id}"
    val extension: String get() = if (language == "java") "java" else "kt"
    fun root(fixtures: Path): Path = if (language == "java") fixtures else fixtures.resolve("kotlin")
}

internal data class DetectionRevision(
    val sha: String, val checkout: String, val classes: String, val origins: String,
    val runId: String, val roots: List<String>, val dependencies: Map<String, String>,
    val files: Map<String, String>,
)

internal data class DetectionDescriptor(
    val fingerprint: String, val repository: String, val base: DetectionRevision, val head: DetectionRevision,
)

/** Test-only storage retains source-compiler inputs, deliberately without a production inventory. */
internal object DetectionFixtures {
    val fixtures: Path = Path.of("../fixtures").toAbsolutePath().normalize()
    val directory: Path = Path.of(System.getProperty("graphfold.detectionFixtures", "build/detection-fixtures"))
        .toAbsolutePath().normalize()
    private val mapper = jacksonObjectMapper()

    fun cases(): List<DetectionCase> {
        val common = mapper.readValue<List<CorpusCase>>(fixtures.resolve("manifest.json").toFile())
        val kotlin = common + mapper.readValue<List<CorpusCase>>(fixtures.resolve("kotlin/features.json").toFile())
        return common.map { DetectionCase("java", it) } + kotlin.map { DetectionCase("kotlin", it) }
    }

    fun selectedCases(): List<DetectionCase> {
        val filter = System.getenv("GRAPHFOLD_CASE_FILTER")?.toRegex()
        val shards = System.getProperty("graphfold.testShards", "1").toInt()
        val shard = System.getProperty("graphfold.testShard", "0").toInt()
        return select(cases(), shards, shard).filter { filter == null || filter.matches(it.case.id) }
    }

    fun select(cases: List<DetectionCase>, shards: Int, shard: Int): List<DetectionCase> {
        require(shards > 0 && shard in 0 until shards) { "Invalid corpus shard $shard/$shards" }
        return cases.filterIndexed { index, _ -> index % shards == shard }
    }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        regularFiles(fixtures).forEach { file ->
            digest.update(fixtures.relativize(file).toString().toByteArray())
            digest.update(Files.readAllBytes(file))
        }
        digest.update(System.getProperty("java.version").toByteArray())
        // These are the compiler implementation inputs, not analysis implementation outputs.
        listOf("SourceFixtureCompiler", "SourceFixtureCompilerKt", "KotlinFixtureCompiler", "CompilerOriginPlugin",
            "FixtureCompilerProcessKt", "DetectionFixtures", "DetectionFixturesKt").forEach { name ->
            val resource = checkNotNull(javaClass.getResourceAsStream("$name.class"))
            digest.update(resource.use { it.readBytes() })
        }
        listOf("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "kotlin.Unit", "org.jetbrains.annotations.NotNull")
            .forEach { digest.update(Files.readAllBytes(classLocation(it))) }
        digest.update(Files.readAllBytes(CompilerOriginPlugin.artifact()))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun descriptor(case: DetectionCase, expectedFingerprint: String): DetectionDescriptor {
        val file = directory.resolve("$case/descriptor.json")
        check(Files.isRegularFile(file)) { "Missing precompiled fixture $case; run :engine:prepareDetectionCorpus" }
        return readDescriptor(file, expectedFingerprint)
    }

    fun readDescriptor(file: Path, expectedFingerprint: String): DetectionDescriptor {
        check(Files.isRegularFile(file)) { "Missing precompiled fixture: $file; run :engine:prepareDetectionCorpus" }
        return mapper.readValue<DetectionDescriptor>(file.toFile()).also {
            check(it.fingerprint == expectedFingerprint) { "Stale precompiled fixture $file; run :engine:prepareDetectionCorpus" }
        }
    }

    fun analyze(descriptor: DetectionDescriptor, output: Path): AnalysisResult {
        fun prepared(revision: DetectionRevision, name: String) = prepareRevision(
            revision.sha, Path.of(revision.checkout), project(revision), output.resolve(name), DependencyInputCache(),
        )
        return GraphFoldEngine(EngineConfiguration(output.resolve("analysis"))).analyze(
            prepared(descriptor.base, "base"), prepared(descriptor.head, "head"),
            Files.readString(fixtures.resolve("fold.yml")),
        )
    }

    fun project(revision: DetectionRevision, directory: Path = this.directory): InputProject {
        val checkout = Path.of(revision.checkout)
        check(checkout.startsWith(directory) && Files.isDirectory(checkout)) {
            "Precompiled corpus must be restored at its original absolute path: $checkout (current root: $directory)"
        }
        revision.files.forEach { (relative, hash) ->
            val file = directory.resolve(relative)
            check(Files.isRegularFile(file) && fileDigest(file) == hash) { "Stale or missing precompiled fixture file: $file" }
        }
        val classes = directory.resolve(revision.classes)
        val origins = directory.resolve(revision.origins)
        check(Files.isDirectory(classes) && Files.isDirectory(origins)) { "Missing precompiled corpus output" }
        val roots = revision.roots.map(checkout::resolve)
        check(roots.all(Files::isDirectory)) { "Missing precompiled corpus source root" }
        val actualFiles = (roots.flatMap(::regularFiles) + regularFiles(classes) + regularFiles(origins))
            .map { directory.relativize(it).toString() }.toSet()
        check(actualFiles == revision.files.keys) { "Precompiled corpus file inventory differs" }
        val dependencies = revision.dependencies.map { (name, hash) ->
            classLocation(name).also { check(fileDigest(it) == hash) { "Stale fixture dependency: $name" } }
        }
        return InputProject(classes, roots, dependencies, origins, revision.runId, roots.associateWith { listOf(classes) })
    }

    fun prepare(case: DetectionCase, fingerprint: String) {
        val target = directory.resolve(case.toString())
        if (Files.exists(target)) target.toFile().deleteRecursively()
        val repository = Files.createDirectories(target.resolve("repository"))
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        val javaSources = Files.createDirectories(repository.resolve("src/main/java/sample"))
        Files.copy(fixtures.resolve("common/Ab.java"), javaSources.resolve("Ab.java"))
        val source = Files.createDirectories(repository.resolve("src/main/${case.language}/sample"))
            .resolve("Example.${case.extension}")
        fun revision(name: String): DetectionRevision {
            Files.copy(case.root(fixtures).resolve("cases/${case.case.id}/$name/Example.${case.extension}"), source,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            git(repository, "add", ".")
            git(repository, "commit", "--quiet", "-m", name)
            val sha = git(repository, "rev-parse", "HEAD").trim()
            val checkout = target.resolve(name)
            git(target, "clone", "--quiet", repository.toString(), checkout.toString())
            val project = SourceFixtureCompiler(Duration.ofMinutes(2)).compile(checkout, target.resolve("$name-output"))
            return describe(sha, checkout, project)
        }
        val base = revision("base")
        val head = revision("head")
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("descriptor.json").toFile(),
            DetectionDescriptor(fingerprint, relative(repository), base, head))
        println("Prepared $case")
    }

    private fun describe(sha: String, checkout: Path, project: InputProject): DetectionRevision = DetectionRevision(
        sha, checkout.toRealPath().toString(), relative(project.classes), relative(checkNotNull(project.originDirectory)),
        checkNotNull(project.originRunId), project.sourceRoots.map { checkout.relativize(it).toString() },
        project.dependencies.associate { dependency ->
            val name = listOf("kotlin.Unit", "org.jetbrains.annotations.NotNull").single { dependency == classLocation(it) }
            name to fileDigest(dependency)
        },
        (project.sourceRoots.flatMap(::regularFiles) + regularFiles(project.classes) +
            regularFiles(checkNotNull(project.originDirectory))).associate { relative(it) to fileDigest(it) },
    )

    private fun relative(path: Path): String = directory.relativize(path).toString()
}

internal fun classLocation(name: String): Path = Path.of(Class.forName(name).protectionDomain.codeSource.location.toURI())
internal fun fileDigest(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

internal fun git(directory: Path, vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments).directory(directory.toFile()).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { text }
    return text
}

/** Invoked only by the explicit Gradle preparation task, never by a test case. */
fun main() {
    val fingerprint = DetectionFixtures.fingerprint()
    DetectionFixtures.cases().forEach { DetectionFixtures.prepare(it, fingerprint) }
}
