import java.util.Arrays;
import java.util.Collections;
import java.util.function.Function;
import java.util.function.Supplier;

public class FlowCases {
    static Object field;
    static class Plain {}
    static class PlainChild extends Plain {}
    static class Custom {
        @Override public String toString() { return String.valueOf(field); }
    }
    static class CustomChild extends Custom {}
    static class CustomHash {
        @Override public int hashCode() { return field.toString().hashCode(); }
    }

    public static String safeObject() { return String.valueOf(new Object()); }
    public static String safeAlias() {
        Object original = new Object();
        Object alias = original;
        return alias.toString();
    }
    public static String safeEscapedAlias() {
        Object original = new Object();
        field = original;
        return original.toString();
    }
    public static String safeMerge(boolean branch) {
        Object value = branch ? new Object() : "literal";
        return String.valueOf(value);
    }
    public static String safeNull() { return String.valueOf((Object) null); }
    public static String safeNullableStaticMerge(boolean branch) {
        Object value = branch ? new Object() : null;
        return String.valueOf(value);
    }
    public static String safeCast() {
        Object value = "literal";
        return ((CharSequence) value).toString();
    }
    public static String safeLoop(int count) {
        Object value = new Object();
        for (int i = 0; i < count; i++) value = new Object();
        return value.toString();
    }
    public static String safeWideConcat(long left, double right) {
        Object value = new Object();
        return left + ":" + value + ":" + right;
    }
    public static String safeInheritedDefault() { return new PlainChild().toString(); }
    public static Supplier<String> safeBoundReference() {
        Object value = new Object();
        return value::toString;
    }
    public static String blockedChanged() { return new Object() {}.toString(); }
    public static String blockedMerge(boolean branch) {
        Object value = branch ? new Object() : new Object() {};
        return String.valueOf(value);
    }
    public static String blockedUnknownMerge(boolean branch, Object parameter) {
        Object value = branch ? new Object() : parameter;
        return value.toString();
    }
    public static String blockedField() { return String.valueOf(field); }
    private static Object factory() { return new Object(); }
    public static String blockedReturn() { return String.valueOf(factory()); }
    public static String blockedCustom() { return new Custom().toString(); }
    public static String blockedInheritedCustom() { return new CustomChild().toString(); }
    public static String blockedHashOverride() { return new CustomHash().toString(); }
    public static String blockedBuilderInsert() {
        StringBuilder builder = new StringBuilder();
        builder.insert(0, new Object() {});
        return builder.toString();
    }
    public static String blockedBufferInsert() {
        StringBuffer buffer = new StringBuffer();
        buffer.insert(0, new Object() {});
        return buffer.toString();
    }
    public static String blockedArray() { return Arrays.deepToString(new Object[] {new Object() {}}); }
    public static String blockedEmptyReferenceArray() { return Arrays.toString(new Object[0]); }
    public static String blockedCollection() { return Collections.singletonList(new Object()).toString(); }
    public static String blockedCast(Object parameter) { return ((Plain) parameter).toString(); }
    public static String blockedLoop(int count) {
        Object value = new Object();
        for (int i = 0; i < count; i++) value = new Object() {};
        return value.toString();
    }
    public static Function<Object, String> blockedUnboundReference() { return Object::toString; }
    public static Supplier<String> blockedBoundReference(Object parameter) { return parameter::toString; }
    public static String blockedNullVirtual() {
        Object value = null;
        return value.toString();
    }
    public static String blockedNullableMerge(boolean branch) {
        Object value = branch ? new Object() : null;
        return value.toString();
    }
    public static String blockedNullBuilder() {
        StringBuilder value = null;
        return value.toString();
    }
    public static String blockedNullBuilderAppend() {
        StringBuilder value = null;
        return value.append(new Object()).toString();
    }
    public static String blockedNullPrimitiveBuilderChain() {
        StringBuilder value = null;
        return value.append(1).toString();
    }
    public static String blockedNullFormat() {
        return String.format((String) null, (Object[]) null);
    }
    public static String blockedNullBuilderConstructor() {
        return new StringBuilder((CharSequence) null).toString();
    }
    public static String blockedWideConcat(long left, double right) {
        Object value = new Object() {};
        return left + ":" + value + ":" + right;
    }
}
