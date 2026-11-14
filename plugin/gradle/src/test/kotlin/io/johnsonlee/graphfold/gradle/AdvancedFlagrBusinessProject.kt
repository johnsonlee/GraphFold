package io.johnsonlee.graphfold.gradle

import java.nio.file.Files
import java.nio.file.Path

/** Six distinct Java mechanisms share one actual packaged business library and the real Flagr client. */
internal class AdvancedFlagrBusinessProject(val root: Path, boot: Boolean) {
    private val project = FlagrBusinessProject(root, boot)

    init {
        replace("business/src/main/java/sample/PricingService.java", "quote(String flag", "quote(String style, String flag")
        val entry = if (boot) "src/main/java/sample/App.java" else "src/main/java/sample/QuoteServlet.java"
        if (boot) {
            replace(entry, "public int quote(",
                "public int quote(@org.springframework.web.bind.annotation.RequestParam(\"style\") String style, ")
            replace(entry, "service.quote(flag,", "service.quote(style, flag,")
        } else replace(entry, "service.quote(request.getParameter(\"flag\"),",
            "service.quote(request.getParameter(\"style\"), request.getParameter(\"flag\"),")
        write("PricingServiceImpl", """
            ${if (boot) "@org.springframework.stereotype.Service" else ""}
            public class PricingServiceImpl implements PricingService {
                public int quote(String style, String flag, String platform, String region) throws Exception {
                    switch (style) {
                        ${styles.joinToString("\n") { "case \"$it\": return $it.quote(flag, platform, region);" }}
                        default: throw new IllegalArgumentException("Unknown quote style");
                    }
                }
            }
        """.trimIndent())
        write("CheckedQuote", "@FunctionalInterface public interface CheckedQuote { int quote() throws Exception; }")
        write("ReceiverQuote", "@FunctionalInterface public interface ReceiverQuote<T> { int quote(T receiver) throws Exception; }")
        write("FunctionalQuotes", """
            public final class FunctionalQuotes {
                public static int invoke(CheckedQuote operation) throws Exception { return operation.quote(); }
            }
        """.trimIndent())
        prices(9000, 10000)
    }

    /** Opt-in regression: unused callback changes are still uncontrolled production changes. */
    fun lazyPrice(amount: Int) {
        val route = root.resolve("business/src/main/java/sample/PricingServiceImpl.java")
        val source = Files.readString(route)
        if ("case \"LazyQuote\"" !in source) {
            Files.writeString(route, source.replace("default: throw", "case \"LazyQuote\": " +
                "return LazyQuote.quote(flag, platform, region); default: throw"))
        }
        write("LazyQuote", """
            public final class LazyQuote {
                public static int quote(String flag, String platform, String region) {
                    java.util.stream.Stream<Integer> deferred = java.util.stream.Stream.of(10000).map(price -> $amount);
                    return 10000;
                }
            }
        """.trimIndent())
    }

    fun commit(): String = project.commit()

    fun prices(enabled: Int, disabled: Int) {
        val decision = "FlagrDecisions.discountEnabled(flag, platform, region) ? $enabled : $disabled"
        write("CapturedQuote", """
            public final class CapturedQuote {
                public static int quote(String flag, String platform, String region) throws Exception {
                    CheckedQuote operation = () -> $decision;
                    return FunctionalQuotes.invoke(operation);
                }
            }
        """.trimIndent())
        write("BoundReferenceQuote", referenceBody(decision, true))
        write("UnboundReferenceQuote", referenceBody(decision, false))
        write("StaticHandleQuote", staticHandle(decision))
        write("BoundHandleQuote", boundHandle(decision))
        write("StreamQuote", """
            public final class StreamQuote {
                public static int quote(String flag, String platform, String region) {
                    java.util.List<Integer> prices = java.util.stream.Stream.of(10000, 2000)
                        .filter(price -> price >= 10000)
                        .map(price -> {
                            try { return $decision; }
                            catch (Exception failure) { throw new IllegalStateException(failure); }
                        }).collect(java.util.stream.Collectors.toList());
                    return prices.stream().reduce(0, Integer::sum);
                }
            }
        """.trimIndent())
    }

    private fun referenceBody(decision: String, bound: Boolean): String {
        val name = if (bound) "BoundReferenceQuote" else "UnboundReferenceQuote"
        val invocation = if (bound) "CheckedQuote operation = receiver::value; return FunctionalQuotes.invoke(operation);"
            else "ReceiverQuote<Binding> operation = Binding::value; return operation.quote(receiver);"
        return """
            public final class $name {
                public static int quote(String flag, String platform, String region) throws Exception {
                    Binding receiver = new Binding(flag, platform, region);
                    $invocation
                }
                ${binding(decision)}
            }
        """.trimIndent()
    }

    private fun staticHandle(decision: String): String = """
        public final class StaticHandleQuote {
            public static int quote(String flag, String platform, String region) {
                Traffic context = new Traffic(flag, platform, region);
                try {
                    java.lang.invoke.MethodHandle handle = java.lang.invoke.MethodHandles.lookup().findStatic(
                        StaticHandleQuote.class, "value", java.lang.invoke.MethodType.methodType(int.class, Traffic.class));
                    return (int) handle.invokeExact(context);
                } catch (Throwable failure) { throw new IllegalStateException(failure); }
            }
            public static int value(Traffic context) throws Exception {
                String flag = context.flag, platform = context.platform, region = context.region;
                return $decision;
            }
            public static final class Traffic {
                final String flag, platform, region;
                public Traffic(String flag, String platform, String region) {
                    this.flag=flag; this.platform=platform; this.region=region;
                }
            }
        }
    """.trimIndent()

    private fun boundHandle(decision: String): String = """
        public final class BoundHandleQuote {
            public static int quote(String flag, String platform, String region) {
                try {
                    java.lang.invoke.MethodHandle handle = java.lang.invoke.MethodHandles.lookup().findVirtual(
                        Binding.class, "value", java.lang.invoke.MethodType.methodType(int.class))
                        .bindTo(new Binding(flag, platform, region));
                    Object boxed = handle.invoke();
                    return ((Integer) boxed).intValue();
                } catch (Throwable failure) { throw new IllegalStateException(failure); }
            }
            ${binding(decision)}
        }
    """.trimIndent()

    private fun binding(decision: String): String = """
        public static final class Binding {
            final String flag, platform, region;
            public Binding(String flag, String platform, String region) {
                this.flag=flag; this.platform=platform; this.region=region;
            }
            public int value() throws Exception { return $decision; }
        }
    """.trimIndent()

    private fun write(name: String, source: String) {
        Files.writeString(root.resolve("business/src/main/java/sample/$name.java"), "package sample;\n$source")
    }

    private fun replace(path: String, before: String, after: String) {
        val file = root.resolve(path)
        val source = Files.readString(file)
        check(before in source)
        Files.writeString(file, source.replace(before, after))
    }

    companion object {
        val styles = listOf("CapturedQuote", "BoundReferenceQuote", "UnboundReferenceQuote",
            "StaticHandleQuote", "BoundHandleQuote", "StreamQuote")
        val sources = styles.map { "business/src/main/java/sample/$it.java" }.toSet()
    }
}
