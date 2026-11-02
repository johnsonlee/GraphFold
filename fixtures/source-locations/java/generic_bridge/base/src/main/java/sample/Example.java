package sample;
interface Value<T> { T value(); }
public class Example implements Value<String> {
    public String value() {
        return "value1";
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
