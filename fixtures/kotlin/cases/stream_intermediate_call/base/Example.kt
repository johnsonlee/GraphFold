package sample
class Example {
    fun run(values: List<Int>): List<Int> {
        if (Ab.enabled("feature")) return java.util.List.of<Int>();
        return values.stream().sorted().toList();
    }
}
