package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) return 0; return new int[]{x, 2}[0]; }
}
