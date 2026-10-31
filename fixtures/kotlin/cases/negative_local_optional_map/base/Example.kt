package sample
class Example {
    fun run(value: Int?): Int = java.util.Optional.ofNullable(value).map { item ->
        if (Ab.enabled("feature")) item + 1 else item * 2
    }.orElse(0)
}
