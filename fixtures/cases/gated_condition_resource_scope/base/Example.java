package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            try (java.io.StringReader reader = new java.io.StringReader("ab")) {
                return reader.read();
            } catch (java.io.IOException error) { return -1; }
        }
        return 0;
    }
}
