package sample;
public class Example {
    public java.util.List<Integer> run(java.util.List<Integer> values) {
        return values.stream().map(value -> {
            if (Ab.enabled("feature")) return value + 20;
            return value + 1;
        }).toList();
    }
}
