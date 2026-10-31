import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r") }
}

plugins {
    base
    jacoco
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
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
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "jacoco")
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }
    extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.13" }
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("detekt.yml"))
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports { sarif.required.set(true); html.required.set(true) }
    }
    dependencies {
        add("testImplementation", kotlin("test-junit5"))
        add("testImplementation", platform("org.junit:junit-bom:5.12.1"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
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
    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports { xml.required.set(true); html.required.set(true) }
    }
    tasks.withType<JacocoCoverageVerification>().configureEach {
        dependsOn(tasks.withType<Test>())
        violationRules {
            rule {
                limit { counter = "LINE"; minimum = "0.90".toBigDecimal() }
                limit { counter = "BRANCH"; minimum = "0.95".toBigDecimal() }
            }
        }
    }
    tasks.named("check") { dependsOn("jacocoTestReport", "jacocoTestCoverageVerification") }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories { maven { name = "releaseVerification"; url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI() } }

        }
    }
}

tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }

// Sonatype's XML metadata and java-gradle-plugin both populate marker POM names.
// Keep the first singleton after every plugin has registered its POM customizations.
gradle.projectsEvaluated {
    subprojects {
        extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublication>()?.configureEach {
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
