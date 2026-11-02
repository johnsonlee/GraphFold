package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            java.util.function.IntUnaryOperator operation = Math::negateExact;
            return operation.applyAsInt(x);
        }
        return 0;
    }
}
