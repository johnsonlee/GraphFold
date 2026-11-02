package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            int sum = 0;
            for (int i = 0; i < x; i++) { if (i == 4) break; sum += i; }
            return sum;
        }
        return 0;
    }
}
