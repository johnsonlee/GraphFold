package io.johnsonlee.graphfold.gradle

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git

/** Packaged applications call the real evaluation endpoint; no local flag evaluator is substituted. */
internal class FlagrBusinessProject(val root: Path, private val boot: Boolean) {
    init {
        Files.createDirectories(root)
        val workspace = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(workspace.resolve(name), target)
        }
        write(".gitignore", ".gradle/\nbuild/\n")
        write("settings.gradle", "rootProject.name='app'; include 'business'")
        write("build.gradle", if (boot) """
            plugins { id 'io.johnsonlee.graphfold'; id 'java'; id 'org.springframework.boot' version '3.5.7' }
            version='1'
            allprojects { repositories { mavenCentral() } }
            dependencies {
                implementation project(':business')
                implementation platform('org.springframework.boot:spring-boot-dependencies:3.5.7')
                implementation 'org.springframework.boot:spring-boot-starter-web'
            }
            springBoot { mainClass='sample.App' }
        """.trimIndent() else """
            plugins { id 'io.johnsonlee.graphfold'; id 'war' }
            version='1'
            allprojects { repositories { mavenCentral() } }
            dependencies { implementation project(':business'); providedCompile 'jakarta.servlet:jakarta.servlet-api:6.0.0' }
        """.trimIndent())
        write("business/build.gradle", """
            plugins { id 'java-library' }
            dependencies {
                implementation platform('org.springframework.boot:spring-boot-dependencies:3.5.7')
                implementation 'org.springframework:spring-context'
                implementation 'com.fasterxml.jackson.core:jackson-databind'
            }
        """.trimIndent())
        write("business/src/main/java/sample/PricingService.java", """
            package sample;
            public interface PricingService { int quote(String flag, String platform, String region) throws Exception; }
        """.trimIndent())
        prices(9000, 10000)
        write("business/src/main/java/sample/FlagrDecisions.java", """
            package sample;
            public final class FlagrDecisions {
                private static final java.net.http.HttpClient CLIENT = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
                private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                    new com.fasterxml.jackson.databind.ObjectMapper();
                public static boolean discountEnabled(String flag, String platform, String region) throws Exception {
                    if (!flag.matches("[a-z-]+") || !platform.matches("[A-Za-z]+") || !region.matches("[A-Z]+"))
                        throw new IllegalArgumentException("Invalid evaluation context");
                    String body = "{\"flagKey\":\"" + flag + "\",\"entityID\":\"golden-customer\"," +
                        "\"entityContext\":{\"platform\":\"" + platform + "\",\"region\":\"" + region + "\"}}";
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(System.getProperty("flagr.url") + "/api/v1/evaluation"))
                        .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
                    java.net.http.HttpResponse<String> response = CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) throw new IllegalStateException("Flagr evaluation failed");
                    String variant = JSON.readTree(response.body())
                        .path("variantKey").asText();
                    if (!variant.equals("on") && !variant.equals("off")) throw new IllegalStateException("Unknown variant");
                    return variant.equals("on");
                }
            }
        """.trimIndent())
        if (boot) bootEntry() else warEntry()
        Git.init().setDirectory(root.toFile()).call().close()
    }

    fun prices(enabled: Int, disabled: Int) = write(SOURCE, """
        package sample;
        ${if (boot) "@org.springframework.stereotype.Service" else ""}
        public class PricingServiceImpl implements PricingService {
            public int quote(String flag, String platform, String region) throws Exception {
                return FlagrDecisions.discountEnabled(flag, platform, region) ? $enabled : $disabled;
            }
        }
    """.trimIndent())

    fun commit(): String = Git.open(root.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Real Flagr business golden").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }

    private fun bootEntry() = write("src/main/java/sample/App.java", """
        package sample;
        @org.springframework.boot.autoconfigure.SpringBootApplication
        @org.springframework.web.bind.annotation.RestController
        public class App {
            private final PricingService service;
            public App(PricingService service) { this.service = service; }
            public static void main(String[] args) { org.springframework.boot.SpringApplication.run(App.class, args); }
            @org.springframework.web.bind.annotation.GetMapping("/quote")
            public int quote(@org.springframework.web.bind.annotation.RequestParam("flag") String flag,
                    @org.springframework.web.bind.annotation.RequestParam("platform") String platform,
                    @org.springframework.web.bind.annotation.RequestParam("region") String region) throws Exception {
                return service.quote(flag, platform, region);
            }
        }
    """.trimIndent())

    private fun warEntry() {
        write("src/main/java/sample/QuoteServlet.java", """
            package sample;
            public final class QuoteServlet extends jakarta.servlet.http.HttpServlet {
                private final PricingService service = new PricingServiceImpl();
                protected void doGet(jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
                    try {
                        response.getWriter().print(service.quote(request.getParameter("flag"),
                            request.getParameter("platform"), request.getParameter("region")));
                    } catch (Exception failure) { throw new java.io.IOException(failure); }
                }
            }
        """.trimIndent())
        write("src/main/webapp/WEB-INF/web.xml", """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0" metadata-complete="true">
              <servlet><servlet-name>quote</servlet-name><servlet-class>sample.QuoteServlet</servlet-class></servlet>
              <servlet-mapping><servlet-name>quote</servlet-name><url-pattern>/quote</url-pattern></servlet-mapping>
            </web-app>
        """.trimIndent())
    }

    private fun write(name: String, text: String) {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    companion object { const val SOURCE = "business/src/main/java/sample/PricingServiceImpl.java" }
}
