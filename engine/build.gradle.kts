plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.sonatype)
}
dependencies {
    implementation(project(":gfc"))
    implementation(libs.jgit.core)
    implementation(libs.jgit.ssh.apache)
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.sootup.core)
    implementation(libs.sootup.java.core)
    implementation(libs.sootup.java.bytecode.frontend)
    implementation(libs.graphite.core)
    implementation(libs.graphite.sootup)
    implementation(libs.graphite.cypher)
    implementation(libs.javaparser)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.asm.commons)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.mockito.core)
}

tasks.withType<Test>().configureEach {
    dependsOn(":gfc:jar")
    forkEvery = 1
    systemProperty("graphfold.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
        .withPropertyName("analysisFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Explicit compilation phase: test JVMs only consume these compiler-emitted inputs.
val prepareDetectionCorpus by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Compile every Java and Kotlin detection fixture before running corpus tests."
    dependsOn(tasks.testClasses, ":gfc:jar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.johnsonlee.graphfold.engine.testing.DetectionFixturesKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    workingDir = projectDir
    maxHeapSize = "1g"
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(sourceSets.test.get().runtimeClasspath).withNormalizer(ClasspathNormalizer::class)
    outputs.dir(layout.buildDirectory.dir("detection-fixtures"))
}
