package sample;
public class Example {
    public int run(int x) {
        int sum = 0;
        outer: for (int i = 0; i < x; i++) {
            for (int j = 0; j < x; j++) { if (j == 4) break outer; sum++; }
        }
        return sum;
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
