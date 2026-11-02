package sample
class Example {
    fun run(trace: StringBuilder, value: Int): Int {
        try {
            if (Ab.enabled("feature")) return value + 1
            return value * 2
        } finally {
            trace.append("finally")
        }
    }
}
