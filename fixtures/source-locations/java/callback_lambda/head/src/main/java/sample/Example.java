package sample;
public class Example {
    public int run(int x) {
        java.util.function.IntUnaryOperator operation = value -> value + 2;
        return operation.applyAsInt(x);
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
