package sample;
public class Example {
    public int run(int x) {
        return java.util.Optional.of(x).map(value -> value + 1).orElse(0);
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
