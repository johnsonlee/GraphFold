package sample;
public class Example {
    private int advance(int x) { return x + 2; }
    public int run(int x) { return advance(x); }
    public boolean gate() { return Ab.enabled("feature"); }
}
