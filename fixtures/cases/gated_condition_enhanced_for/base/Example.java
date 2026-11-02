package sample;
public class Example {
    public int run(int[] values) {
        if (Ab.enabled("feature")) {
            int sum = 0;
            for (int value : values) { if (value > 0) sum += value; }
            return sum;
        }
        return 0;
    }
}
