package io.johnsonlee.graphfold.gradle

import java.nio.file.Files
import java.nio.file.Path

/** Static platform × region matrix: new safe exposure conditions and edits behind existing conditions. */
internal class ExposureFlagrBusinessProject(val root: Path, boot: Boolean) {
    private val project = FlagrBusinessProject(root, boot)

    init {
        replace("business/src/main/java/sample/PricingService.java", "quote(String flag", "quote(String style, String flag")
        if (boot) {
            replace("src/main/java/sample/App.java", "public int quote(",
                "public int quote(@org.springframework.web.bind.annotation.RequestParam(\"style\") String style, ")
            replace("src/main/java/sample/App.java", "service.quote(flag,", "service.quote(style, flag,")
        } else replace("src/main/java/sample/QuoteServlet.java", "service.quote(request.getParameter(\"flag\"),",
            "service.quote(request.getParameter(\"style\"), request.getParameter(\"flag\"),")
        write("PricingServiceImpl", """
            ${if (boot) "@org.springframework.stereotype.Service" else ""}
            public class PricingServiceImpl implements PricingService {
                public int quote(String style, String flag, String platform, String region) throws Exception {
                    ${policies.joinToString("\n") { policy ->
                        "if (style.equals(\"${policy.name}-new\")) return New${policy.name}Quote.quote(flag, platform, region);\n" +
                            "if (style.equals(\"${policy.name}-existing\")) return " +
                            "Existing${policy.name}Quote.quote(flag, platform, region);"
                    }}
                    throw new IllegalArgumentException("Unknown exposure style");
                }
            }
        """.trimIndent())
        revision(false)
    }

    fun commit(): String = project.commit()

    fun revision(head: Boolean) {
        policies.forEach { policy ->
            val prefix = policy.expression.takeIf { it.isNotEmpty() }?.let { "($it) && " }.orEmpty()
            write("New${policy.name}Quote", """
                public final class New${policy.name}Quote {
                    public static int quote(String flag, String platform, String region) throws Exception {
                        return ${if (head) prefix else ""}FlagrDecisions.discountEnabled(flag, platform, region) ? 9000 : 10000;
                    }
                }
            """.trimIndent())
            write("Existing${policy.name}Quote", """
                public final class Existing${policy.name}Quote {
                    public static int quote(String flag, String platform, String region) throws Exception {
                        return ${prefix}FlagrDecisions.discountEnabled(flag, platform, region) ? ${if (head) 8500 else 9000} : 10000;
                    }
                }
            """.trimIndent())
        }
    }

    private fun replace(path: String, before: String, after: String) {
        val file = root.resolve(path)
        val source = Files.readString(file)
        check(before in source)
        Files.writeString(file, source.replace(before, after))
    }

    private fun write(name: String, source: String) =
        Files.writeString(root.resolve("business/src/main/java/sample/$name.java"), "package sample;\n$source")

    private data class ExposurePolicy(val name: String, val expression: String)

    companion object {
        private val platforms = listOf("All" to "", "Single" to "\"Android\".equals(platform)",
            "Multiple" to "(\"Android\".equals(platform) || \"iOS\".equals(platform))")
        private val regions = listOf("All" to "", "Single" to "\"KR\".equals(region)",
            "Multiple" to "(\"KR\".equals(region) || \"US\".equals(region))")
        private val policies = platforms.flatMap { (platform, platformPredicate) ->
            regions.map { (region, regionPredicate) ->
                val expression = listOf(platformPredicate, regionPredicate).filter { it.isNotEmpty() }.joinToString(" && ")
                ExposurePolicy(platform + region, expression)
            }
        }
        val policyNames = policies.map { it.name }
        val controlCalls = policies.size * 2
        val sources = policies.flatMap { policy ->
            // The all/all new-exposure case has no predicate to add and therefore no source edit.
            listOfNotNull("Existing${policy.name}Quote", "New${policy.name}Quote".takeIf { policy.expression.isNotEmpty() })
        }.map { "business/src/main/java/sample/$it.java" }.toSet()
    }
}
