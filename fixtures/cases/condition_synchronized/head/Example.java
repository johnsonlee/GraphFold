package sample;
public class Example {
    public int run(int x) {
        synchronized (this) { return x + 2; }
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
