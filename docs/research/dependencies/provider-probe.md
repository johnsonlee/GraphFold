# Module service factory activation

A module can register a service factory with `provides` even when the factory class does not implement the service interface. This matters for resource proofs: `ResourceBundle.getBundle()` can activate a `ResourceBundleControlProvider` factory through platform service discovery, without an explicit application call to the factory.

This isolated correctness probe was verified with OpenJDK **17.0.20.1** on 2026-11-02. It does not run GraphFold or establish a full engine verdict. It establishes that scanning only class superclasses and implemented interfaces cannot prove that this implicit resource-consumer path is absent.

## Reproduce

Run this script with `javac` and `java` on `PATH`; it creates all inputs in a fresh directory:

```shell
probe_dir="$(mktemp -d)"
mkdir -p "$probe_dir/src/provider/factory"
cat > "$probe_dir/src/provider/module-info.java" <<'JAVA'
module provider {
    provides java.util.spi.ResourceBundleControlProvider with factory.Factory;
}
JAVA
cat > "$probe_dir/src/provider/factory/Factory.java" <<'JAVA'
package factory;

public final class Factory {
    public static java.util.spi.ResourceBundleControlProvider provider() {
        System.setProperty("graphfold.factory", "invoked");
        return null;
    }
}
JAVA
cat > "$probe_dir/Main.java" <<'JAVA'
public class Main {
    public static void main(String[] args) {
        try {
            java.util.ResourceBundle.getBundle("messages");
        } catch (Throwable ignored) {
            // Returning null deliberately makes service creation fail after the observable effect.
        }
        System.out.println("factory=" + System.getProperty("graphfold.factory"));
    }
}
JAVA
printf 'old=Hello\n' > "$probe_dir/messages.properties"
javac -d "$probe_dir/mods" --module-source-path "$probe_dir/src" \
    "$probe_dir/src/provider/module-info.java" "$probe_dir/src/provider/factory/Factory.java"
javac -d "$probe_dir" "$probe_dir/Main.java"
java --module-path "$probe_dir/mods" --add-modules provider -cp "$probe_dir" Main
```

Observed output:

```text
factory=invoked
```

`Factory` has no implemented service interface and only extends `Object`. Its registered factory runs because of module metadata. The marker is set before the deliberate service-construction failure, so the observed activation does not depend on successfully returning a custom provider object.

## GraphFold boundary

Production inventory inspects module metadata for both application classes and runtime dependencies. Any `provides` entry makes resource-consumer scope unknown until module-driven service activation is modeled. This includes root and versioned `module-info.class` entries. The parser still rejects malformed metadata; it uses the same code/debug/frame skipping flags as the ordinary declaration scan.

The guard intentionally covers every provided service, not only `ResourceBundleControlProvider`: excluding other services would require proving how platform and framework discovery activates them. This can conservatively block an unrelated module service. Modules containing only exports, requires or uses remain eligible, including the actual Kotlin stdlib descriptor used by the dependency regression. Eligibility still requires the other inventory and graph proof conditions.

[`DependencyProductionInventoryTest`](../../../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/build/DependencyProductionInventoryTest.kt) covers service factories without implemented interfaces, an unrelated service, application and dependency descriptors, directory and JAR layouts, versioned metadata, malformed metadata, and the no-provider control. The broader dependency integration and its limits are documented in [README.md](README.md).

This probe is correctness evidence only and is not a representative multi-graph performance measurement.
