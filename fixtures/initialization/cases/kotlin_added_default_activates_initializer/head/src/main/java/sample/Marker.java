package sample;
interface Marker {
    int TOKEN = initialize();
    static int initialize() { System.out.println("marker"); return 1; }
    default int value() { return 0; }
}
