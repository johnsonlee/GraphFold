package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) return treatment(x); return x + 1; }
    private int treatment(int x) { return x * 10; }
}
