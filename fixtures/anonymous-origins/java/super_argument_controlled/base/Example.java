package sample;
public class Example {
    static class Base { final int delta; Base(int delta) { this.delta = delta; } public int applyAsInt(int x) { return x + delta; } }
    public int run(int x) { if (!Ab.enabled("feature")) return x; return new Base(1) { public int applyAsInt(int value) { return super.applyAsInt(value); } }.applyAsInt(x); }
    public boolean gate() { return Ab.enabled("feature"); }
}
