package sample;
public class Example {
    public int run(java.util.List<Integer> values) {
        int[] sum = {0};
        values.forEach(value -> sum[0] += value);
        return sum[0];
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
