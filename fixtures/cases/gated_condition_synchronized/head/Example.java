package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            synchronized (this) { return x + 2; }
        }
        return 0;
    }
}
