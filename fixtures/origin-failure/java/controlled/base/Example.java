package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) return 42; return x + 1; }
    public boolean gate() { return Ab.enabled("feature"); }
}
