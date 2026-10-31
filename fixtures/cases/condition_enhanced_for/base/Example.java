package sample;
public class Example {
    public int run(int[] values) {
        int sum = 0;
        for (int value : values) { if (value > 0) sum += value; }
        return sum;
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
