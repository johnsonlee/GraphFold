package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            java.util.function.IntUnaryOperator operation = value -> value + 1;
            return operation.applyAsInt(x);
        }
        return 0;
    }
}
