package io.johnsonlee.graphfold.compiler.protocol;

/** Shared wire vocabulary; javac must load this without a Kotlin runtime. */
public final class OriginProtocol {
    private OriginProtocol() {}

    public enum Compiler {
        JAVAC("javac"), KOTLINC("kotlinc");

        private final String wireValue;

        Compiler(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum Backend { JVM }

    public enum KotlinFrontend { K1, K2 }

    public enum Kind { ANONYMOUS_OBJECT, DECLARATION }

    public enum Provenance { INLINE_COPY, DIRECT_IR }
}
