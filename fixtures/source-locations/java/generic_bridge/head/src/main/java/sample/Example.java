package sample;
interface Value<T> { T value(); }
public class Example implements Value<String> {
    public String value() {
        return "value2";
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
