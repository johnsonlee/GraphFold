package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            int offset = x;
            java.util.function.IntUnaryOperator operation = value -> value + offset + 2;
            return operation.applyAsInt(x);
        }
        return 0;
    }
}
