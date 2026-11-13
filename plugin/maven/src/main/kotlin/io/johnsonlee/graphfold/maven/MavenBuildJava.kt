package io.johnsonlee.graphfold.maven

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import org.apache.maven.toolchain.ToolchainManager
import org.codehaus.plexus.util.xml.Xpp3Dom

/** Selects archive definitions for the JDK used by this Maven compilation, independently of the analyzer host. */
internal fun buildJavaVersion(project: MavenProject, session: MavenSession, manager: ToolchainManager): Int {
    val compiler = project.buildPlugins.firstOrNull { it.key == "org.apache.maven.plugins:maven-compiler-plugin" }
    val plugin = compiler?.configuration as? Xpp3Dom
    val execution = compiler?.executions?.firstOrNull { "compile" in it.goals || it.id == "default-compile" }
        ?.configuration as? Xpp3Dom
    val configuration = Xpp3Dom.mergeXpp3Dom(execution?.let(::Xpp3Dom), plugin?.let(::Xpp3Dom))
    fun setting(name: String): String? = configuration?.getChild(name)?.value
        ?: session.userProperties.getProperty("maven.compiler.$name")
        ?: project.properties.getProperty("maven.compiler.$name")
    val executable = setting("executable")
    if (executable != null) {
        return if (setting("fork").toBoolean()) jdkFeature(project.basedir.toPath().resolve(executable))
        else sessionFeature(session)
    }
    val requirements = configuration?.getChild("jdkToolchain")?.children?.associate { it.name to it.value }
    val selected = requirements?.let { manager.getToolchains(session, "jdk", it).orEmpty().firstOrNull() }
        ?: manager.getToolchainFromBuildContext("jdk", session)
    return if (selected == null) sessionFeature(session)
    else jdkFeature(Path.of(requireNotNull(selected.findTool("javac")) { "Selected Maven JDK has no javac path" }))
}

private fun sessionFeature(session: MavenSession): Int = javaFeature(
    requireNotNull(session.systemProperties.getProperty("java.version")) { "Maven session did not record its Java version" })

private fun jdkFeature(javac: Path): Int {
    val release = javac.toRealPath().parent.parent.resolve("release")
    val properties = Properties().apply { Files.newInputStream(release).use(::load) }
    return javaFeature(requireNotNull(properties.getProperty("JAVA_VERSION")) { "JDK release lacks JAVA_VERSION: $release" })
}

private fun javaFeature(version: String): Int {
    val normalized = version.trim().trim('"').removePrefix("1.")
    return normalized.takeWhile(Char::isDigit).toInt().also { require(it > 0) { "Invalid build Java version: $version" } }
}
