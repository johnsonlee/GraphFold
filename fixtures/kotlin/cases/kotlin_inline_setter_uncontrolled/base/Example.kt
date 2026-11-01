package sample

class Example {
    private var state: Int = 0
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return 42
        bump = x
        return state
    }
    private var bump: Int
        get() = state
        inline set(value) { state = value + 1 }
}
