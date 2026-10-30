package sample;
public class Example {
    public int run(int x) {
        java.util.function.IntUnaryOperator operation = new java.util.function.IntUnaryOperator() {
            public int applyAsInt(int value) { return value + 1; }
        };
        return operation.applyAsInt(x);
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
