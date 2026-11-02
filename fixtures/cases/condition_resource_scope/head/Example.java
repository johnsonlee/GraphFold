package sample;
public class Example {
    public int run(int x) {
        try (java.io.StringReader reader = new java.io.StringReader("ab")) {
            reader.read();
            return reader.read();
        } catch (java.io.IOException error) { return -1; }
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
