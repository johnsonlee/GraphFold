package sample;
public class Example {
    public int run(java.util.List<Integer> values) {
        return values.stream().reduce(0, (sum, value) -> {
            if (Ab.enabled("feature")) return sum + value + 1;
            return sum + value;
        });
    }
}
