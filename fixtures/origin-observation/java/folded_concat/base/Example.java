package sample;
import java.util.function.Supplier;
public class Example {
    public String run() {
        return new Supplier<String>() { public String get() { if (Ab.enabled("feature")) { return "callback=" + this; } return "live"; } }.get();
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
