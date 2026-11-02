package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            return java.util.Optional.of(x).map(value -> value + 2).orElse(0);
        }
        return 0;
    }
}
