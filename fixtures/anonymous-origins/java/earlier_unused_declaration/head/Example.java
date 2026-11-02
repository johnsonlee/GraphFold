package sample;
import java.util.function.IntUnaryOperator;
public class Example {
    private IntUnaryOperator unused() { return new IntUnaryOperator() { public int applyAsInt(int value) { return value + 99; } }; }
    public int run(int x) {
        IntUnaryOperator live = new IntUnaryOperator() { public int applyAsInt(int value) { return value + 10; } };
        return live.applyAsInt(x);
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
