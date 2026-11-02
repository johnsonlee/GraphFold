package sample;
public class Example {
    public enum Mode { FIRST, SECOND }
    public int run(Mode mode) {
        switch (mode) {
        case FIRST: return 11;
        default: return 20;
        }
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
