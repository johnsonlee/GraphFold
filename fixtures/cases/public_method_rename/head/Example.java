package sample;
public class Example {
    public int advance(int x) { return x + 1; }
    public int run(int x) { return advance(x); }
    public boolean gate() { return Ab.enabled("feature"); }
}
