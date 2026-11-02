package sample

class Example {
    enum class Mode { FIRST, SECOND }
    fun run(mode: Mode): Int {
        when (mode) {
            Mode.FIRST -> return 11
            Mode.SECOND -> return 20
        }
    }
    fun gate(): Boolean = Ab.enabled("feature")
}
