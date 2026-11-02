package sample;
public class Example {
    public int run(int x) { new Additional(); return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
class Additional {
    static { System.out.println("initialized"); }
}
