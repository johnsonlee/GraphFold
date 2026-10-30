package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            return x > 0 && x < 10 ? 1 : 0;
        }
        return 0;
    }
}
