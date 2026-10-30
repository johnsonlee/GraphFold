plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.sonatype)
}
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
dependencies {
    implementation(project(":engine"))
    testImplementation(gradleTestKit())
    testImplementation(libs.jackson.kotlin)
    testImplementation("org.mockito:mockito-core:5.16.1")
}
