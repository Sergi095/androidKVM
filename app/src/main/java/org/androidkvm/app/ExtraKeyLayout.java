package org.androidkvm.app;

/** The extra-key rows shared by the KVM screen and NanoKVM terminals. */
final class ExtraKeyLayout {
    static final String[][] FIRST = {
            {"ESC", "Escape"}, {"/", "Slash"}, {"−", "Minus"},
            {"HOME", "Home"}, {"↑", "ArrowUp"}, {"END", "End"}, {"PGUP", "PageUp"}
    };
    static final String[][] SECOND = {
            {"TAB", "Tab"}, {"CTRL", "ControlLeft"}, {"ALT", "AltLeft"},
            {"←", "ArrowLeft"}, {"↓", "ArrowDown"}, {"→", "ArrowRight"}, {"PGDN", "PageDown"}
    };
    static final String[][] FUNCTIONS = functionKeys();

    private static String[][] functionKeys() {
        String[][] keys = new String[14][2];
        keys[0][0] = "WIN/⌘";
        keys[0][1] = "MetaLeft";
        for (int number = 1; number <= 12; number++) {
            keys[number][0] = "F" + number;
            keys[number][1] = "F" + number;
        }
        keys[13][0] = "DEL";
        keys[13][1] = "Delete";
        return keys;
    }

    private ExtraKeyLayout() { }
}
