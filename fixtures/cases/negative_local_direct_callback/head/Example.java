package sample;
public class Example {
    public int run(int value) {
        java.util.function.IntUnaryOperator callback = item -> {
            if (Ab.enabled("feature")) return item + 2;
            return item * 2;
        };
        return callback.applyAsInt(value);
    }
}
