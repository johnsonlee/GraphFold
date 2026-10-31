package sample;
public class Example {
    public int run(int x) {
        int sum = 0;
        do { sum += x; x--; } while (x > 1);
        return sum;
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
