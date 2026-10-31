package sample;
public class Example {
    public int run(int value, int offset) {
        java.util.function.IntUnaryOperator callback = item -> {
            if (Ab.enabled("feature")) return item + offset + 2;
            return item + offset;
        };
        return callback.applyAsInt(value);
    }
}
