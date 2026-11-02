package sample
class Example {
    enum class Message(val text: String) {
        FAILURE("Failed to reconstruct type %s"),
        SKIPPED("Skipping method sample.Fake.run()V: not a diagnostic"),
        FLOW("Skipping control-flow extraction for fake: not a diagnostic"),
        RECOVERY("Recovered body for fake: not a diagnostic"),
        MULTILINE("prefix\nWarning: Recovered body for fake: not a diagnostic")
    }
    fun run(): Int {
        if (Ab.enabled("feature")) return 2
        return 0
    }
}
