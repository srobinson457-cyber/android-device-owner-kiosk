package dev.deviceowner.kiosk;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory SharedPreferences for JVM tests. The android.jar used by unit tests only returns
 * default values, so a real SharedPreferences would silently store nothing.
 *
 * Edits apply when apply() or commit() is called, and the last write to a key in one editor
 * wins, as on a device.
 */
final class FakePrefs implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();

    @Override public Map<String, ?> getAll() { return new HashMap<>(values); }

    @Override public String getString(String k, String def) {
        return values.containsKey(k) ? (String) values.get(k) : def;
    }

    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String k, Set<String> def) {
        return values.containsKey(k) ? new HashSet<>((Set<String>) values.get(k)) : def;
    }

    @Override public int getInt(String k, int def) {
        return values.containsKey(k) ? (Integer) values.get(k) : def;
    }

    @Override public long getLong(String k, long def) {
        return values.containsKey(k) ? (Long) values.get(k) : def;
    }

    @Override public float getFloat(String k, float def) {
        return values.containsKey(k) ? (Float) values.get(k) : def;
    }

    @Override public boolean getBoolean(String k, boolean def) {
        return values.containsKey(k) ? (Boolean) values.get(k) : def;
    }

    @Override public boolean contains(String k) { return values.containsKey(k); }

    @Override public Editor edit() { return new FakeEditor(); }

    @Override public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener l) {}

    @Override public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener l) {}

    private final class FakeEditor implements Editor {
        private static final Object REMOVE = new Object();
        private final Map<String, Object> pending = new LinkedHashMap<>();
        private boolean clear;

        @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) {
            pending.put(k, v == null ? null : new HashSet<>(v));
            return this;
        }
        @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
        @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
        @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
        @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
        @Override public Editor remove(String k) { pending.put(k, REMOVE); return this; }
        @Override public Editor clear() { clear = true; return this; }

        @Override public boolean commit() {
            if (clear) values.clear();
            for (Map.Entry<String, Object> e : pending.entrySet()) {
                if (e.getValue() == REMOVE || e.getValue() == null) values.remove(e.getKey());
                else values.put(e.getKey(), e.getValue());
            }
            pending.clear();
            clear = false;
            return true;
        }

        @Override public void apply() { commit(); }
    }
}
