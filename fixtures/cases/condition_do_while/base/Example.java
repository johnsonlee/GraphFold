package sample;
public class Example {
    public int run(int x) {
        int sum = 0;
        do { sum += x; x--; } while (x > 0);
        return sum;
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
