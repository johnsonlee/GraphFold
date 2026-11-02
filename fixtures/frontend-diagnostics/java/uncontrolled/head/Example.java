package sample;
public class Example {
    public enum Message {
        FAILURE("Failed to reconstruct type %s"),
        SKIPPED("Skipping method sample.Fake.run()V: not a diagnostic"),
        FLOW("Skipping control-flow extraction for fake: not a diagnostic"),
        RECOVERY("Recovered body for fake: not a diagnostic"),
        MULTILINE("prefix\nWarning: Recovered body for fake: not a diagnostic");
        private final String text;
        Message(String text) { this.text = text; }
        public String text() { return text; }
    }
    public int run() {
        if (Ab.enabled("feature")) return 1;
        return 2;
    }
}
