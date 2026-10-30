package sample
class Example {
    fun run(values: List<Int>): List<Int> {
        if (Ab.enabled("feature")) return values.stream().filter { x -> x > 0 && x < 10 }.toList();
        return values;
    }
}
