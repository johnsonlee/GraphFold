package sample;
public class Example {
    public int run(int[] values) {
        int sum = 0;
        for (int value : values) {
            sum += value;
            if (Ab.enabled("feature")) sum += 2;
        }
        return sum;
    }
}
