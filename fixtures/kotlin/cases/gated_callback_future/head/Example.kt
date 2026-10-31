package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return java.util.concurrent.CompletableFuture.completedFuture(x)
                .thenApply { value -> value + 2 }.join()
        }
        return 0
    }
}
