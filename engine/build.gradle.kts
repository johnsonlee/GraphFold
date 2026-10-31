plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.sonatype)
}
dependencies {
    implementation(project(":gfc")) {
        attributes { attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR)) }
    }
    implementation(libs.auto.service.annotations)
    ksp(libs.auto.service.ksp)
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.8.0.202609011348-r")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.1.20")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20")
    implementation("org.soot-oss:sootup.core:3.0.1")
    implementation("org.soot-oss:sootup.java.core:3.0.1")
    implementation("org.soot-oss:sootup.java.bytecode.frontend:3.0.1")
    implementation("io.johnsonlee.graphite:core:2.12.0")
    implementation("io.johnsonlee.graphite:sootup:2.12.0")
    implementation("io.johnsonlee.graphite:cypher:2.12.0")
    implementation(libs.javaparser)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation("org.ow2.asm:asm-commons:${libs.versions.asm.get()}")
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    testImplementation("com.networknt:json-schema-validator:1.5.9")
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named("jar"))
    systemProperty("graphfold.test.engineJar", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
        .withPropertyName("analysisFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Gradle isolates test classloaders; java.class.path need not contain the test runtime.
    systemProperty("graphfold.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

// Shared regression helpers are local build variants, not part of the public engine SDK.
components.named<AdhocComponentWithVariants>("java") {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}
