package sample;
public class Example {
    public int run(int x) {
        return java.util.concurrent.CompletableFuture.completedFuture(x)
            .thenApply(value -> value + 2).join();
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
