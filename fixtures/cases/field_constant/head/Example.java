package sample;
public class Example {
    private static final int LIMIT = 20;
    public int run(int x) { if (Ab.enabled("feature")) return 0; return x + LIMIT; }
}
