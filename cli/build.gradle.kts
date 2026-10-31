plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.sonatype)
}
application {
    applicationName = "graphfold"
    mainClass.set("io.johnsonlee.graphfold.cli.MainKt")
}
dependencies {
    implementation(project(":engine"))
    testImplementation(testFixtures(project(":engine")))
    implementation(libs.clikt)
    implementation(libs.jackson.kotlin)
}

// Resolve the same version used by publications and distribution archives, including -Pversion releases.
val releaseVersion = project.version.toString()
tasks.named<ProcessResources>("processResources") {
    inputs.property("releaseVersion", releaseVersion)
    filesMatching("io/johnsonlee/graphfold/cli/version.txt") { expand("version" to releaseVersion) }
}
tasks.withType<Test>().configureEach {
    systemProperty("graphfold.expectedVersion", releaseVersion)
}
