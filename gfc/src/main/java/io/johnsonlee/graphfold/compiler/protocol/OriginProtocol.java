package io.johnsonlee.graphfold.compiler.protocol;

/**
 * Shared vocabulary for compiler provenance JSON and bytecode markers.
 * Enum names are wire values unless a separate {@code wireValue()} is provided. This class has no
 * Kotlin runtime dependency so it can also be loaded in an isolated javac process.
 */
public final class OriginProtocol {
    private OriginProtocol() {}

    /** Compiler that produced a provenance document. */
    public enum Compiler {
        /** The JDK Java compiler, identified as {@code javac} in JSON. */
        JAVAC("javac"),
        /** The Kotlin compiler, identified as {@code kotlinc} in JSON. */
        KOTLINC("kotlinc");

        private final String wireValue;

        Compiler(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the compiler identifier stored in the document's producer metadata.
         *
         * @return the lowercase protocol value, independent of the enum constant's name
         */
        public String wireValue() {
            return wireValue;
        }
    }

    /** Backend responsible for emitting the recorded classes. */
    public enum Backend {
        /** JVM class files. */
        JVM
    }

    /** Kotlin frontend used by the compilation, recorded separately from the compiler version. */
    public enum KotlinFrontend {
        /** The classic Kotlin frontend. */
        K1,
        /** The FIR-based Kotlin frontend. */
        K2
    }

    /** Source construct represented by a Kotlin origin record or marker. */
    public enum Kind {
        /** An anonymous object expression written in source, excluding known backend-generated classes. */
        ANONYMOUS_OBJECT,
        /** Another declaration with a verifiable physical source range. */
        DECLARATION
    }

    /** Route by which a Kotlin emitted class obtained its source origin. */
    public enum Provenance {
        /** Origin recovered from a marker copied during backend inlining. */
        INLINE_COPY,
        /** Origin captured directly from the class's IR declaration. */
        DIRECT_IR
    }
}
