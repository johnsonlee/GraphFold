package sample;
import java.util.function.IntUnaryOperator;
public class Example {
    public int run(int x) {
        IntUnaryOperator outer = new IntUnaryOperator() { public int applyAsInt(int input) {
            IntUnaryOperator inner = new IntUnaryOperator() { public int applyAsInt(int value) { return value + 2; } };
            return inner.applyAsInt(input);
        } };
        return outer.applyAsInt(x);
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
