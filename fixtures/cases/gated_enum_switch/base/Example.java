package sample;
public class Example {
    public enum Mode { FIRST, SECOND }
    public int run(Mode mode) {
        if (Ab.enabled("feature")) {
            switch (mode) {
            case FIRST: return 10;
            default: return 20;
            }
        }
        return 0;
    }
}
