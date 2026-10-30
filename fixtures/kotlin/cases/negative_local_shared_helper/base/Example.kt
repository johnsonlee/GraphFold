package sample
class Example {
    fun run(value: Int): Int {
        var result = helper(value)
        if (Ab.enabled("feature")) result += helper(value + 1)
        return result
    }
    private fun helper(value: Int): Int {
        if (Ab.enabled("feature")) return value + 1
        return value * 2
    }
}
