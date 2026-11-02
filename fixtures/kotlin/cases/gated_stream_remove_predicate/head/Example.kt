package sample
class Example {
    fun run(values: List<Int>): List<Int> {
        if (Ab.enabled("feature")) return values.stream().filter { x -> x > 0 }.toList();
        return values;
    }
}
