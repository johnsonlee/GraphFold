package sample;
public class Example {
    public int run(int x) {
        int sum = 0;
        while (x > 1) { sum += x; x--; }
        return sum;
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
