package sample;
public class Example {
    public int run(int x) { if (!Ab.enabled("feature")) return x + 1; return x * 10; }
}
