package com.opencode.eclipse.ui;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/** Minimal in-memory {@link IEclipsePreferences}: real Eclipse preference nodes require a
 * running OSGi/Platform instance, which this bare-JVM test harness doesn't have. */
public final class FakePreferences implements IEclipsePreferences {
	private final Map<String, String> values = new HashMap<>();
	public boolean flushed;

	@Override public void put(String key, String value) { values.put(key, value); }
	@Override public String get(String key, String def) { return values.getOrDefault(key, def); }
	@Override public void remove(String key) { values.remove(key); }
	@Override public void clear() { values.clear(); }
	@Override public void flush() { flushed = true; }
	@Override public void sync() { }

	@Override public void putInt(String key, int value) { values.put(key, String.valueOf(value)); }
	@Override public int getInt(String key, int def) { return values.containsKey(key) ? Integer.parseInt(values.get(key)) : def; }
	@Override public void putLong(String key, long value) { values.put(key, String.valueOf(value)); }
	@Override public long getLong(String key, long def) { return values.containsKey(key) ? Long.parseLong(values.get(key)) : def; }
	@Override public void putBoolean(String key, boolean value) { values.put(key, String.valueOf(value)); }
	@Override public boolean getBoolean(String key, boolean def) { return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : def; }
	@Override public void putFloat(String key, float value) { values.put(key, String.valueOf(value)); }
	@Override public float getFloat(String key, float def) { return values.containsKey(key) ? Float.parseFloat(values.get(key)) : def; }
	@Override public void putDouble(String key, double value) { values.put(key, String.valueOf(value)); }
	@Override public double getDouble(String key, double def) { return values.containsKey(key) ? Double.parseDouble(values.get(key)) : def; }
	@Override public void putByteArray(String key, byte[] value) { throw new UnsupportedOperationException(); }
	@Override public byte[] getByteArray(String key, byte[] def) { throw new UnsupportedOperationException(); }
	@Override public String[] keys() { return values.keySet().toArray(new String[0]); }
	@Override public String[] childrenNames() { return new String[0]; }
	@Override public Preferences parent() { return null; }
	@Override public Preferences node(String pathName) { throw new UnsupportedOperationException(); }
	@Override public boolean nodeExists(String pathName) { return false; }
	@Override public void removeNode() { }
	@Override public String name() { return "fake"; }
	@Override public String absolutePath() { return "/fake"; }
	@Override public void addNodeChangeListener(INodeChangeListener listener) { }
	@Override public void removeNodeChangeListener(INodeChangeListener listener) { }
	@Override public void addPreferenceChangeListener(IPreferenceChangeListener listener) { }
	@Override public void removePreferenceChangeListener(IPreferenceChangeListener listener) { }
	@Override public void accept(org.eclipse.core.runtime.preferences.IPreferenceNodeVisitor visitor) throws BackingStoreException { }
}
