package sample;
public class Example {
    public int run(int x) {
        int offset = x;
        java.util.function.IntUnaryOperator operation = value -> value + offset + 2;
        return operation.applyAsInt(x);
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
