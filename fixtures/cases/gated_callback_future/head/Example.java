package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            return java.util.concurrent.CompletableFuture.completedFuture(x)
                .thenApply(value -> value + 2).join();
        }
        return 0;
    }
}
