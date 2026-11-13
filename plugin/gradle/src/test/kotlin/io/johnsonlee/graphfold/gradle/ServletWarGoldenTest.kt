package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.ReasonCode
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.security.MessageDigest
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServletWarGoldenTest {
    @TempDir lateinit var temporary: Path

    @Test fun `real filter request wrapper changes remain uncontrolled production findings`() {
        val repository = repository()
        val wrapper = "src/main/java/sample/ParameterRequest.java"
        write(repository, wrapper, """
            package sample;
            public final class ParameterRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
                public ParameterRequest(jakarta.servlet.http.HttpServletRequest request) { super(request); }
                public String getParameter(String name) { return "before"; }
            }
        """.trimIndent())
        write(repository, "src/main/java/sample/ParameterFilter.java", """
            package sample;
            public final class ParameterFilter implements jakarta.servlet.Filter {
                public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                        jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    chain.doFilter(new ParameterRequest((jakarta.servlet.http.HttpServletRequest) request), response);
                }
            }
        """.trimIndent())
        val servlet = repository.resolve("src/main/java/sample/ValueServlet.java")
        Files.writeString(servlet, Files.readString(servlet).replace("Logic.value()", "request.getParameter(\"value\") + Logic.value()"))
        val descriptor = repository.resolve("src/main/webapp/WEB-INF/web.xml")
        Files.writeString(descriptor, Files.readString(descriptor).replace("</web-app>", """
              <filter><filter-name>parameter</filter-name><filter-class>sample.ParameterFilter</filter-class></filter>
              <filter-mapping><filter-name>parameter</filter-name><url-pattern>/*</url-pattern></filter-mapping>
            </web-app>
        """.trimIndent()))
        val base = commit(repository)
        Files.writeString(repository.resolve(wrapper), Files.readString(repository.resolve(wrapper)).replace("before", "after"))
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.Gate, callee_name: enabled}}
                    value: false
            """.trimIndent()))
        val wars = packagedWars()
        assertEquals(2, wars.size, result.toString())
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), "Wrapper change must be a precise finding: $result")
        assertTrue(result.findings.flatMap { it.locations }.any { it.path == wrapper }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `actual WAR library changes have exact static control verdicts`(live: Boolean) {
        val repository = repository()
        val base = commit(repository)
        write(repository, LOGIC, logic(43, if (live) 8 else 7))
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.Gate, callee_name: enabled}}
                    value: false
            """.trimIndent()))
        val wars = packagedWars()
        assertEquals(2, wars.size, "Both independently built WAR revisions must exist: $result")
        assertEquals(if (live) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), "Servlet WAR happy-path analysis must be complete: $result")
        assertEquals(live, result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == LOGIC }, result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 },
            result.toString())
        val reason = if (live) ReasonCode.SURVIVING_DIFFERENCE else ReasonCode.FOLDED_EQUIVALENT
        assertTrue(result.reasons.any { it.code == reason }, result.toString())
    }

    private fun repository(): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(name), target)
        }
        write(root, ".gitignore", ".gradle/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='tomcat-proof'; include 'logic'")
        write(root, "build.gradle", """
            plugins { id 'io.johnsonlee.graphfold'; id 'war' }
            repositories { mavenCentral() }
            dependencies {
                implementation project(':logic')
                providedCompile 'jakarta.servlet:jakarta.servlet-api:6.0.0'
            }
        """.trimIndent())
        write(root, "logic/build.gradle", "plugins { id 'java-library' }")
        write(root, "logic/src/main/java/sample/Gate.java", """
            package sample;
            public class Gate { public static boolean enabled() { return false; } }
        """.trimIndent())
        write(root, LOGIC, logic(42, 7))
        write(root, "src/main/java/sample/ValueServlet.java", """
            package sample;
            public final class ValueServlet extends jakarta.servlet.http.HttpServlet {
                protected void doGet(jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
                    response.getWriter().print(Logic.value());
                }
            }
        """.trimIndent())
        write(root, "src/main/webapp/WEB-INF/web.xml", """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0" metadata-complete="true">
              <servlet><servlet-name>value</servlet-name><servlet-class>sample.ValueServlet</servlet-class></servlet>
              <servlet-mapping><servlet-name>value</servlet-name><url-pattern>/value</url-pattern></servlet-mapping>
            </web-app>
        """.trimIndent())
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    private fun packagedWars(): List<Path> = Files.walk(temporary.resolve("cache")).use { paths ->
        paths.filter { it.fileName.toString() == "tomcat-proof.war" }.toList().distinctBy {
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)))
        }
    }

    private fun logic(controlled: Int, live: Int): String = """
        package sample;
        public class Logic { public static int value() { if (Gate.enabled()) return $controlled; return $live; } }
    """.trimIndent()

    private fun write(root: Path, name: String, text: String) {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Servlet WAR golden").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }

    private companion object {
        const val LOGIC = "logic/src/main/java/sample/Logic.java"
    }
}
