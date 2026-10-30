package sample;
public class Example {
    public java.util.List<Integer> run(java.util.List<Integer> values) {
        if (Ab.enabled("feature")) return values.stream().filter(x -> x > 0 && x < 10).toList();
        return values;
    }
}
