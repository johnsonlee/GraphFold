package sample
class Example {
    private var offset = 1
    fun run(): String { return dependency.Encoder.encode(this) }
}
