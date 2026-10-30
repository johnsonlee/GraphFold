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
    implementation(libs.clikt)
    implementation(libs.jackson.kotlin)
}
