package sample;
public class Example {
    public int run(int x) {
        synchronized (this) { return x + 1; }
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
