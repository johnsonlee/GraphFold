package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            switch (x) {
            case 1: return 11;
            case 2: return 20;
            default: return 30;
            }
        }
        return 0;
    }
}
