package sample;
public class Example {
    public java.util.List<Integer> run(java.util.List<Integer> values) {
        return values.stream().filter(value -> {
            if (Ab.enabled("feature")) return value > 2;
            return value > 0;
        }).toList();
    }
}
