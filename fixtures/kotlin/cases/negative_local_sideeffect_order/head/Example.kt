package sample
class Example {
    fun run(trace: StringBuilder, value: Int): Int {
        trace.append("before")
        var result = value
        if (Ab.enabled("feature")) result += 2
        trace.append("after")
        return result
    }
}
