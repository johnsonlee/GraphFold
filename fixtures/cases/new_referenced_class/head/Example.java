package sample;
public class Example {
    public int run(int x) { return new Additional().apply(x); }
    public boolean gate() { return Ab.enabled("feature"); }
}
class Additional {
    public int apply(int x) { return x + 1; }
}
