package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            if (x <= 0) throw new IllegalArgumentException();
            return x;
        }
        return 0;
    }
}
