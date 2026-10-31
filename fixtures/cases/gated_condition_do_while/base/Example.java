package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            int sum = 0;
            do { sum += x; x--; } while (x > 0);
            return sum;
        }
        return 0;
    }
}
