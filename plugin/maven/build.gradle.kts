import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.tools.plugin.ExtendedPluginDescriptor
import org.gradlex.maven.plugin.development.task.GAV
import org.gradlex.maven.plugin.development.task.GenerateMavenPluginDescriptorTask
import org.gradlex.maven.plugin.development.task.MavenPluginDescriptor
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.sonatype)
    alias(libs.plugins.maven.plugin.development) apply false
}

base { archivesName.set("graphfold-maven-plugin") }

/** Maven Plugin Tools reads compiled annotations; retain the provider's minimum host versions. */
@CacheableTask
abstract class GraphFoldPluginDescriptor : GenerateMavenPluginDescriptorTask() {
    @get:Input abstract val minimumJavaVersion: Property<String>
    @get:Input abstract val minimumMavenVersion: Property<String>

    override fun createPluginDescriptor(): PluginDescriptor = ExtendedPluginDescriptor(super.createPluginDescriptor()).apply {
        requiredJavaVersion = minimumJavaVersion.get()
        requiredMavenVersion = minimumMavenVersion.get()
    }
}

val generateMavenPluginDescriptor by tasks.registering(GraphFoldPluginDescriptor::class) {
    classesDirs.from(tasks.named<KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory },
        tasks.named<JavaCompile>("compileJava").flatMap { it.destinationDirectory })
    sourcesDirs.from(sourceSets.main.get().allJava.srcDirs)
    upstreamProjects.set(emptyList())
    // Maven resolves provider dependencies from the published POM, as it does for the existing plugin.
    runtimeDependencies.set(emptyList())
    pluginDescriptor.set(provider {
        MavenPluginDescriptor(GAV.of(project.group.toString(), base.archivesName.get(), project.version.toString()),
            "GraphFold Maven Plugin", "Analyze static control coverage from Maven's current model.", "graphfold")
    })
    minimumJavaVersion.set(java.toolchain.languageVersion.map { it.asInt().toString() })
    minimumMavenVersion.set(libs.versions.maven.api)
    outputDirectory.set(layout.buildDirectory.dir("generated/mavenPluginDescriptor"))
}

dependencies {
    implementation(libs.asm.tree)
    implementation(libs.jackson.kotlin)
    implementation(libs.jgit.core)
    implementation(project(":engine"))
    compileOnly(libs.maven.plugin.annotations)
    compileOnly(libs.javax.inject)
    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.core)
    testImplementation(libs.maven.plugin.api)
    testImplementation(libs.maven.core)
    testImplementation(libs.mockito.core)
}

tasks.processResources {
    from(generateMavenPluginDescriptor)
}

tasks.withType<Test>().configureEach {
    systemProperty("graphfold.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
