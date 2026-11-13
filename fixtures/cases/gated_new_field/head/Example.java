package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) return new Treatment().apply(x); return x + 1; }
    private static class Treatment {
        private final int factor = Integer.parseInt("10");
        int apply(int x) { return x * factor; }
    }
}
