package com.opencode.eclipse.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

final class ChatPreferences {
    private static final String NODE = "com.opencode.eclipse.ui";
    private ChatPreferences() { }
    static int fontSize() {
        return InstanceScope.INSTANCE.getNode(NODE).getInt("chatFontSize", 13);
    }
    static IEclipsePreferences node() { return InstanceScope.INSTANCE.getNode(NODE); }
    static void setFontSize(int size) { node().putInt("chatFontSize", size); }
}
