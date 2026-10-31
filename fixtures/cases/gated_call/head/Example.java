package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) log(x); return x + 1; }
    private void log(int x) { System.out.println(x); }
}
