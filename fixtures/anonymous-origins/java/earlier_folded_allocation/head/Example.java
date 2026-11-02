package sample;
import java.util.function.IntUnaryOperator;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) { (new IntUnaryOperator() { public int applyAsInt(int value) { return value + 99; } }).applyAsInt(x); }
        return (new IntUnaryOperator() { public int applyAsInt(int value) { return value + 10; } }).applyAsInt(x);
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
