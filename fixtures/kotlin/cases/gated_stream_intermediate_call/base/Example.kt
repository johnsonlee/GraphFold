package sample
class Example {
    fun run(values: List<Int>): List<Int> {
        if (Ab.enabled("feature")) return values.stream().sorted().toList();
        return values;
    }
}
