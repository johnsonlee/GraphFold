package sample;
public class Example {
    public int run(java.util.List<Integer> values) {
        if (Ab.enabled("feature")) {
            int[] sum = {0};
            values.forEach(value -> sum[0] += value * 2);
            return sum[0];
        }
        return 0;
    }
}
