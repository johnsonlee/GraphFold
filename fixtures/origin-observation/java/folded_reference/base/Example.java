package sample;
import java.util.function.Supplier;
public class Example {
    public String run() {
        return new Supplier<String>() { public String get() { if (Ab.enabled("feature")) { Supplier<String> render = this::toString; return render.get(); } return "live"; } }.get();
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
