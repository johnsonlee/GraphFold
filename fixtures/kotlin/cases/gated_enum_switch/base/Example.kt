package sample

class Example {
    enum class Mode { FIRST, SECOND }
    fun run(mode: Mode): Int {
        if (Ab.enabled("feature")) {
            when (mode) {
                Mode.FIRST -> return 10
                Mode.SECOND -> return 20
            }
        }
        return 0
    }
}
