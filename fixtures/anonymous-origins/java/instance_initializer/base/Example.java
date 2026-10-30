package sample;
import java.util.function.IntUnaryOperator;
public class Example {
    private final IntUnaryOperator action;
    { action = new IntUnaryOperator() { public int applyAsInt(int value) { return value + 1; } }; }
    public int run(int x) { return action.applyAsInt(x); }
    public boolean gate() { return Ab.enabled("feature"); }
}
