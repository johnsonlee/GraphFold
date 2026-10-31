plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    `java-library`
    alias(libs.plugins.sonatype)
}

dependencies {
    compileOnly(libs.auto.service.annotations)
    ksp(libs.auto.service.ksp)
    // Compiler APIs and Kotlin runtime come from the host compiler, never from the provider JAR.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20")
    testImplementation(libs.jackson.kotlin)
    testImplementation(libs.asm.tree)
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named("jar"))
    systemProperty("graphfold.test.compilerPluginJar", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
}
