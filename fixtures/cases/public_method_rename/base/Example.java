package sample;
public class Example {
    public int increment(int x) { return x + 1; }
    public int run(int x) { return increment(x); }
    public boolean gate() { return Ab.enabled("feature"); }
}
