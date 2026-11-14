import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata
import java.nio.ByteBuffer
import java.security.MessageDigest

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath(libs.jgit.core) }
}

plugins {
    base
    jacoco
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.sonatype)
}

// Official releases supply -Pversion. Development artifacts identify the configured base and exact Git revision.
// The lazy fallback skips our Git lookup when a release version is supplied.
val artifactVersion = providers.gradleProperty("version").orElse(providers.provider {
    val baseVersion = providers.gradleProperty("baseVersion").get()
    val builder = FileRepositoryBuilder().findGitDir(rootDir).setMustExist(true)
    if (builder.gitDir == null) {
        throw GradleException("Cannot derive development version without Git metadata; supply -Pversion=<version>")
    }
    builder.build().use { repository ->
        val head = repository.resolve(Constants.HEAD)
            ?: throw GradleException("Cannot derive development version without a Git HEAD; supply -Pversion=<version>")
        "$baseVersion-${head.name.take(12)}"
    }
}).get()

allprojects {
    group = "io.johnsonlee.graphfold"
    version = artifactVersion
    repositories {
        mavenCentral()
        // The upstream release publishes independently to Central and GitHub Packages.
        maven {
            name = "graphitePackages"
            url = uri("https://maven.pkg.github.com/johnsonlee/graphite")
            content { includeGroup("io.johnsonlee.graphite") }
            credentials {
                username = providers.gradleProperty("graphitePackagesUsername")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("graphitePackagesToken")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("detekt.yml"))
            basePath = rootProject.projectDir.absolutePath
            ignoreFailures = false
        }
        tasks.withType<Detekt>().configureEach {
            jvmTarget = "17"
            reports {
                sarif.required.set(true)
                html.required.set(true)
                xml.required.set(true)
            }
        }
        // Include type-resolved main, test and test-fixtures analysis in the required gate.
        tasks.named("check") { dependsOn(tasks.withType<Detekt>()) }
    }
}

val coverageDataDirectory = providers.gradleProperty("graphfold.coverageDataDirectory").orNull

val sharedLibraries = libs
val implementationProjects = subprojects.filter { it.path != ":plugin" }

// Analyze handwritten build logic without scanning fixture projects or generated outputs.
detekt {
    source.setFrom(files("build.gradle.kts", "settings.gradle.kts", implementationProjects.map { it.buildFile }))
}

configure(implementationProjects) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "jacoco")
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }
    extensions.configure<JacocoPluginExtension> { toolVersion = sharedLibraries.versions.jacoco.get() }
    dependencies {
        add("testImplementation", kotlin("test-junit5"))
        add("testImplementation", platform(sharedLibraries.junit.bom))
        add("testImplementation", sharedLibraries.junit.jupiter)
        add("testRuntimeOnly", sharedLibraries.junit.platform.launcher)
    }
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("notimestamp", true)
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        inputs.property("corpusFilter", providers.environmentVariable("GRAPHFOLD_CASE_FILTER").orElse(""))
        maxHeapSize = "2g"
        testLogging { events("failed", "skipped") }
    }
    // Only engine aggregates integration evidence for its single production artifact.
    // Plugins measure their native build-model integration; shared input decoding has one engine owner.
    val coverageProjects = if (path == ":engine") implementationProjects else listOf(this)
    tasks.withType<JacocoReportBase>().configureEach {
        if (coverageDataDirectory == null) {
            dependsOn(coverageProjects.map { "${it.path}:test" })
            executionData.setFrom(coverageProjects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
        } else {
            executionData.setFrom(coverageProjects.map { covered ->
                rootProject.fileTree(coverageDataDirectory) {
                    include("shard-*/${covered.path.removePrefix(":").replace(':', '/')}/build/jacoco/test.exec")
                }
            })
        }
    }
    tasks.withType<JacocoReport>().configureEach {
        reports { xml.required.set(true); html.required.set(true) }
    }
    tasks.withType<JacocoCoverageVerification>().configureEach {
        violationRules {
            rule {
                limit { counter = "LINE"; minimum = "1.00".toBigDecimal() }
                limit { counter = "BRANCH"; minimum = "1.00".toBigDecimal() }
            }
        }
    }
    tasks.named("check") { dependsOn("jacocoTestReport", "jacocoTestCoverageVerification") }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "releaseVerification"
                    url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
                }
                maven {
                    name = "GitHubPackages"
                    url = uri(providers.gradleProperty("githubPackagesRepository")
                        .orElse("https://maven.pkg.github.com/johnsonlee/graphfold").get())
                    // File repositories exercise the exact publication tasks without remote credentials.
                    if (url.scheme != "file") credentials {
                        username = providers.gradleProperty("githubPackagesUsername")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("githubPackagesPassword")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }

        }
    }
}

tasks.named("check") { dependsOn(implementationProjects.map { "${it.path}:check" }) }

// Sonatype's XML metadata and java-gradle-plugin both populate marker POM names.
// Keep the first singleton after every plugin has registered its POM customizations.
gradle.projectsEvaluated {
    configure(implementationProjects) {
        val implementationName = name
        val mavenPlugin = path == ":plugin:maven"
        val publicationArtifact = when (path) {
            ":plugin:gradle" -> "gradle-plugin"
            ":plugin:maven" -> "graphfold-maven-plugin"
            else -> name
        }
        extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublication>()?.configureEach {
            if (artifactId == implementationName) artifactId = publicationArtifact
            if (mavenPlugin) pom.packaging = "maven-plugin"
            pom.withXml {
                val root = asNode()
                val singletonNames = setOf("name", "description", "url", "licenses", "developers", "scm")
                root.children().filterIsInstance<groovy.util.Node>().groupBy { it.name().toString().substringAfterLast('}') }
                    .filterKeys { it in singletonNames }.values.forEach { nodes ->
                        nodes.drop(1).forEach { root.remove(it) }
                    }
            }
        }
    }
}


/** The same top-level class always belongs to the same Actions shard, including nested JUnit classes. */
fun testClassShard(className: String, shards: Int): Int {
    val name = className.substringBefore('$')
    val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
    return (ByteBuffer.wrap(digest).int.toUInt().toLong() % shards).toInt()
}

// Resolve ordinary test settings after each integration has arranged its required host classpath.
gradle.projectsEvaluated {
    val engine = project(":engine")
    val engineSources = engine.extensions.getByType<SourceSetContainer>()
    project(":plugin:gradle").tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
        // The Gradle plugin registers this collection as an extra input on every Test task.
        // Keep producer dependencies on metadata generation, while readers receive plain files.
        val producers = project.files(pluginClasspath.from.toList())
        dependsOn(producers)
        pluginClasspath.setFrom(project.files(producers.files))
    }
    configure(implementationProjects) {
        val sources = extensions.getByType<SourceSetContainer>()
        val ordinary = tasks.named<Test>("test").get()
        val shardCount = providers.gradleProperty("graphfold.testShards").orElse("1").get().toInt()
        val shard = providers.gradleProperty("graphfold.testShard").orElse("0").get().toInt()
        require(shardCount > 0 && shard in 0 until shardCount) { "Invalid test shard $shard/$shardCount" }
        val inventoryFile = layout.buildDirectory.file("test-inventory.json").get().asFile
        val discoveredClasses by lazy {
            check(inventoryFile.isFile) { "Missing restored test inventory: $inventoryFile; run writeTestInventory first" }
            (groovy.json.JsonSlurper().parse(inventoryFile) as List<*>).map { requireNotNull(it as? String) }.toSet()
        }
        val validatePrecompiledInputs = tasks.register("validatePrecompiledInputs") {
            mustRunAfter(ordinary.taskDependencies.getDependencies(ordinary))
            doLast {
                check(discoveredClasses.isNotEmpty()) { "Restored JUnit inventory is empty" }
                check(ordinary.testClassesDirs.files.any { directory ->
                    directory.isDirectory && directory.walkTopDown().any { it.extension == "class" }
                }) { "Missing restored test classes for ${project.path}" }
                val missingJars = ordinary.classpath.files.filter { it.extension == "jar" && !it.isFile }
                check(missingJars.isEmpty()) { "Missing restored test classpath: $missingJars" }
            }
        }
        tasks.register<Test>("precompiledTest") {
            description = "Run restored compiled tests without invoking any compiler or fixture preparation."
            group = "verification"
            // Plain files intentionally detach source-set/project artifact build dependencies.
            setDependsOn(listOf(validatePrecompiledInputs))
            mustRunAfter(ordinary.taskDependencies.getDependencies(ordinary))
            testClassesDirs = files(ordinary.testClassesDirs.files)
            classpath = files(ordinary.classpath.files)
            systemProperties = ordinary.systemProperties.toMap()
            systemProperty("graphfold.testShard", shard)
            systemProperty("graphfold.testShards", shardCount)
            javaLauncher.set(ordinary.javaLauncher)
            jvmArgs = ordinary.jvmArgs
            maxHeapSize = ordinary.maxHeapSize
            forkEvery = ordinary.forkEvery
            maxParallelForks = 1
            workingDir = ordinary.workingDir
            inputs.property("testShard", shard)
            inputs.property("testShards", shardCount)
            inputs.file(inventoryFile).withPathSensitivity(PathSensitivity.RELATIVE)
            include { element ->
                val name = element.relativePath.pathString.removeSuffix(".class").replace('/', '.')
                // Jupiter discovers nested tests through their selected top-level container.
                element.isDirectory || name in discoveredClasses &&
                    (name == "io.johnsonlee.graphfold.engine.DetectionCorpusTest" || testClassShard(name, shardCount) == shard)
            }
            if (shardCount > 1) filter.isFailOnNoMatchingTests = false
            reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/test"))
            reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/test"))
            binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/test/binary"))
            extensions.configure<JacocoTaskExtension> {
                destinationFile = layout.buildDirectory.file("jacoco/test.exec").get().asFile
            }
        }
        tasks.register<JavaExec>("writeTestInventory") {
            description = "Discover this module's JUnit test classes without executing tests."
            group = "verification"
            dependsOn(tasks.named("testClasses"), engine.tasks.named("testClasses"))
            classpath = files(ordinary.classpath, engineSources.named("test").get().runtimeClasspath)
            mainClass.set("io.johnsonlee.graphfold.engine.testing.TestInventoryMain")
            javaLauncher.set(ordinary.javaLauncher)
            args(sources.named("test").get().output.classesDirs.asPath,
                layout.buildDirectory.file("test-inventory.json").get().asFile.absolutePath)
            outputs.file(layout.buildDirectory.file("test-inventory.json"))
            inputs.files(sources.named("test").get().output.classesDirs).withNormalizer(ClasspathNormalizer::class)
        }
        if (coverageDataDirectory != null) {
            tasks.withType<JacocoReportBase>().configureEach {
                setDependsOn(emptyList<Any>())
                classDirectories.setFrom(files(classDirectories.files))
                sourceDirectories.setFrom(files(sourceDirectories.files))
                additionalClassDirs.setFrom(files(additionalClassDirs.files))
                additionalSourceDirs.setFrom(files(additionalSourceDirs.files))
            }
        }
    }
    engine.tasks.named("test") { dependsOn(engine.tasks.named("prepareDetectionCorpus")) }
}
