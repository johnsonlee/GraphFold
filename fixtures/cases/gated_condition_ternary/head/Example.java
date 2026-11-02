package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            return x >= 0 ? 1 : 2;
        }
        return 0;
    }
}
