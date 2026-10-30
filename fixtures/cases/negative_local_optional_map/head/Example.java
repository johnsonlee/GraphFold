package sample;
public class Example {
    public int run(Integer value) {
        return java.util.Optional.ofNullable(value).map(item -> {
            if (Ab.enabled("feature")) return item + 2;
            return item * 2;
        }).orElse(0);
    }
}
