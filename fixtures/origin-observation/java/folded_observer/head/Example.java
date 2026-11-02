package sample;
import java.util.function.Supplier;
public class Example {
    public String run() {
        if (Ab.enabled("feature")) { new Supplier<String>() { public String get() { return "unused"; } }.get(); }
        return new Supplier<String>() { public String get() { if (Ab.enabled("feature")) { return toString(); } return "live"; } }.get();
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
