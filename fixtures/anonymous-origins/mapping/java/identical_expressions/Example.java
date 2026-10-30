package sample;
import java.util.function.IntUnaryOperator;
public class Example {
    public int run(int x) { IntUnaryOperator first = new IntUnaryOperator() { public int applyAsInt(int value) { return value + 1; } }; IntUnaryOperator second = new IntUnaryOperator() { public int applyAsInt(int value) { return value + 1; } }; return first.applyAsInt(x) + second.applyAsInt(x); }
    public boolean gate() { return Ab.enabled("feature"); }
}
