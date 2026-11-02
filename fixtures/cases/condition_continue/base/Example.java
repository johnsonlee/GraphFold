package sample;
public class Example {
    public int run(int x) {
        int sum = 0;
        for (int i = 0; i < x; i++) { if (i == 3) continue; sum += i; }
        return sum;
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
