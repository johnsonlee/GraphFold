plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.sonatype)
}

base { archivesName.set("gradle-plugin") }
gradlePlugin {
    website.set("https://github.com/johnsonlee/GraphFold")
    vcsUrl.set("https://github.com/johnsonlee/GraphFold")
    plugins {
        create("graphfold") {
            id = "io.johnsonlee.graphfold"
            implementationClass = "io.johnsonlee.graphfold.gradle.GraphFoldPlugin"
            displayName = "GraphFold"
            description = "Detect uncontrolled code changes through conditional folding"
            tags.set(listOf("verification", "static-analysis", "git"))
        }
    }
}
// Keep Gradle host APIs out of the published plugin dependency graph.
val gradleApiDependency = configurations.api.get().dependencies.single()
configurations.api.get().dependencies.remove(gradleApiDependency)
val gradleTestHost by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
// ProjectBuilder needs Gradle's SLF4J API and binding, both embedded in its aggregate API JAR.
// Standalone logging dependencies otherwise select a non-Gradle Logger implementation.
configurations.testRuntimeClasspath {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "slf4j-nop")
}
dependencies {
    implementation(libs.asm.tree)
    implementation(libs.jgit.core)
    add(gradleTestHost.name, gradleApiDependency)
    compileOnly(gradleApiDependency)
    testImplementation(gradleApiDependency)
    implementation(project(":engine"))
    implementation(libs.jackson.kotlin)
    testImplementation(gradleTestKit())
    testImplementation(libs.mockito.core)
}

tasks.withType<Test>().configureEach {
    systemProperty("graphfold.test.repository", rootProject.projectDir.absolutePath)
    // Compiler and graph libraries retain process-wide state across unrelated fixture repositories.
    // Isolate correctness suites without increasing their heap budget or changing analyzer behavior.
    forkEvery = 1
    dependsOn(tasks.named("jar"))
    // Gradle's aggregate API embeds old third-party libraries, including JavaParser.
    // Keep host APIs available for ProjectBuilder without shadowing the analyzer's dependencies.
    classpath = sourceSets.test.get().runtimeClasspath.minus(gradleTestHost).plus(gradleTestHost)
    systemProperty("graphfold.test.runtimeClasspath", classpath.asPath)
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
        .withPropertyName("analysisFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}
