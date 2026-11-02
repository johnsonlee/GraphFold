package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            int sum = 0;
            while (x > 0) { sum += x; x--; }
            return sum;
        }
        return 0;
    }
}
