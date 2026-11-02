package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) { for (int i = 0; i < x; i++) x += 2; return x; } return x + 1; }
}
