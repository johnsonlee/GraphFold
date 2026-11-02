package sample;
public class Example {
    public int run(int x) {
        return switch (x) { case 1 -> 11; case 2 -> 20; default -> 30; };
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
