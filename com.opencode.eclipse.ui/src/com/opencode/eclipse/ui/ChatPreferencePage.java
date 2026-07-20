package com.opencode.eclipse.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public final class ChatPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
    public ChatPreferencePage() { super(GRID); setPreferenceStore(new org.eclipse.jface.preference.PreferenceStore()); }
    @Override protected void createFieldEditors() {
        IntegerFieldEditor size = new IntegerFieldEditor("chatFontSize", "Chat font size (px):", getFieldEditorParent());
        size.setValidRange(10, 24); addField(size);
    }
    @Override public void init(IWorkbench workbench) {
        setPreferenceStore(new org.eclipse.jface.preference.PreferenceStore());
        getPreferenceStore().setValue("chatFontSize", InstanceScope.INSTANCE.getNode("com.opencode.eclipse.ui").getInt("chatFontSize", 13));
    }
    @Override public boolean performOk() {
        boolean ok = super.performOk();
        if (ok) ChatPreferences.setFontSize(getPreferenceStore().getInt("chatFontSize"));
        return ok;
    }
}
