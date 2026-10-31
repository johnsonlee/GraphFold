package sample;
public class Example {
    public int run(int x) {
        if (x < 0) throw new IllegalArgumentException();
        return x;
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
