package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            java.util.function.IntUnaryOperator operation = Math::abs;
            return operation.applyAsInt(x);
        }
        return 0;
    }
}
