package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return 42
        return bump
    }
    private inline val bump: Int get() = 2
}
