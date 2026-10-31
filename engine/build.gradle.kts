plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.sonatype)
}
dependencies {
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
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
        .withPropertyName("analysisFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Gradle isolates test classloaders; java.class.path need not contain the test runtime.
    systemProperty("graphfold.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

val compilerOriginServices = tasks.register("compilerOriginServices") {
    val destination = layout.buildDirectory.dir("generated/compiler-origin-services")
    outputs.dir(destination)
    doLast {
        val services = destination.get().dir("META-INF/services").asFile.apply { mkdirs() }
        mapOf(
            "com.sun.source.util.Plugin" to "io.johnsonlee.graphfold.engine.origin.javac.GraphFoldOrigins",
            "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar" to
                "io.johnsonlee.graphfold.engine.origin.kotlin.KotlinOriginRegistrar",
            "org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor" to
                "io.johnsonlee.graphfold.engine.origin.kotlin.KotlinOriginCommandLineProcessor",
        ).forEach { (service, provider) -> services.resolve(service).writeText("$provider\n") }
    }
}
val compilerOriginJar = tasks.register<Jar>("compilerOriginJar") {
    archiveFileName.set("compiler-plugin.jar")
    destinationDirectory.set(layout.buildDirectory.dir("compiler-origin"))
    dependsOn(tasks.named("compileJava"), tasks.named("compileKotlin"))
    from(sourceSets.main.get().output.classesDirs) {
        include("io/johnsonlee/graphfold/engine/origin/javac/**", "io/johnsonlee/graphfold/engine/origin/kotlin/**")
    }
    from(compilerOriginServices)
}
tasks.named<ProcessResources>("processResources") {
    from(compilerOriginJar) { into("engine/origin") }
}

// Shared regression helpers are local build variants, not part of the public engine SDK.
components.named<AdhocComponentWithVariants>("java") {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}
