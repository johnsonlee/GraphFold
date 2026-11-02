package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            int sum = 0;
            outer: for (int i = 0; i < x; i++) {
                for (int j = 0; j < x; j++) { if (j == 3) break outer; sum++; }
            }
            return sum;
        }
        return 0;
    }
}
