package sample

class Example {
    fun run(x: Int): Int {
        return java.util.concurrent.CompletableFuture.completedFuture(x)
            .thenApply { value -> value + 2 }.join()
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
