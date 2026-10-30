package sample;
public class Example {
    public java.util.List<Integer> run(java.util.List<Integer> values) {
        if (Ab.enabled("feature")) return values.stream().filter(x -> x > 0).toList();
        return values;
    }
}
