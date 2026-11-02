package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) return helper(x); return helper(x); }
    private int helper(int x) { return x + 1; }
}
