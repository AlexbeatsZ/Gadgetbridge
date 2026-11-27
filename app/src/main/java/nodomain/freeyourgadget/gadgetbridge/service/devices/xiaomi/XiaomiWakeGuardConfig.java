/* SPDX-License-Identifier: AGPL-3.0-or-later */

package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Weekly, device-local awake periods. A cross-midnight period belongs to its starting day. */
public final class XiaomiWakeGuardConfig {
    public static final String PREF_SCREEN = "xiaomi_wake_guard";
    public static final String PREF_ENABLED = "xiaomi_wake_guard_enabled";
    public static final String PREF_RULES = "xiaomi_wake_guard_rules";
    public static final String PREF_STATUS = "xiaomi_wake_guard_status";
    public static final String PREF_SAMPLE_AT = "xiaomi_wake_guard_sample_at";
    public static final String TEST_VIBRATE = "xiaomi_wake_guard_test_vibrate";
    public static final String TEST_RING = "xiaomi_wake_guard_test_ring";
    public static final String STOP_RING = "xiaomi_wake_guard_stop_ring";
    public static final int MAX_RULES = 32;

    public enum Mode { VIBRATE, RING }

    public static final class Rule {
        public final String id, name;
        public final boolean enabled;
        public final int days, startMinute, endMinute, confirmationMinutes, repeatMinutes;
        public final Mode mode;

        public Rule(String id, String name, boolean enabled, int days, int startMinute,
                    int endMinute, Mode mode, int confirmationMinutes, int repeatMinutes) {
            if (id == null || id.isEmpty() || name == null || name.trim().isEmpty()
                    || name.length() > 80 || days <= 0 || days > 127
                    || startMinute < 0 || startMinute >= 1440 || endMinute < 0 || endMinute >= 1440
                    || startMinute == endMinute || mode == null
                    || confirmationMinutes < 1 || confirmationMinutes > 30
                    || repeatMinutes < 1 || repeatMinutes > 60) {
                throw new IllegalArgumentException("Invalid wake guard rule");
            }
            this.id = id;
            this.name = name.trim();
            this.enabled = enabled;
            this.days = days;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.mode = mode;
            this.confirmationMinutes = confirmationMinutes;
            this.repeatMinutes = repeatMinutes;
        }

        public boolean matches(ZonedDateTime now) {
            if (!enabled) return false;
            int day = now.getDayOfWeek().getValue() - 1;
            int minute = now.getHour() * 60 + now.getMinute();
            if (startMinute < endMinute) {
                return (days & (1 << day)) != 0 && minute >= startMinute && minute < endMinute;
            }
            if (minute >= startMinute) return (days & (1 << day)) != 0;
            return minute < endMinute && (days & (1 << ((day + 6) % 7))) != 0;
        }

        /** Distinguish consecutive occurrences, including a clock/date change between samples. */
        public String occurrence(ZonedDateTime now) {
            return id + ":" + (startMinute > endMinute && now.getHour() * 60 + now.getMinute() < endMinute
                    ? now.toLocalDate().minusDays(1) : now.toLocalDate());
        }
    }

    public final boolean enabled;
    public final List<Rule> rules;

    public XiaomiWakeGuardConfig(boolean enabled, List<Rule> rules) {
        this.enabled = enabled;
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("Too many wake guard rules");
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    /** Ring takes priority when periods overlap; ties retain list order. */
    public Rule activeRule(ZonedDateTime now) {
        if (!enabled) return null;
        Rule selected = null;
        for (Rule rule : rules) {
            if (rule.matches(now) && (selected == null || (selected.mode == Mode.VIBRATE && rule.mode == Mode.RING))) {
                selected = rule;
            }
        }
        return selected;
    }

    public static List<Rule> parseRules(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        if (array.length() > MAX_RULES) throw new JSONException("Too many wake guard rules");
        List<Rule> rules = new ArrayList<>();
        try {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Rule rule = new Rule(o.getString("id"), o.getString("name"), o.getBoolean("enabled"),
                        o.getInt("days"), o.getInt("start"), o.getInt("end"), Mode.valueOf(o.getString("mode")),
                        o.getInt("confirm"), o.getInt("repeat"));
                for (Rule previous : rules) {
                    if (previous.id.equals(rule.id)) throw new IllegalArgumentException("Duplicate rule id");
                }
                rules.add(rule);
            }
        } catch (IllegalArgumentException e) {
            throw new JSONException(e.getMessage());
        }
        return rules;
    }

    public static String serializeRules(List<Rule> rules) throws JSONException {
        JSONArray array = new JSONArray();
        for (Rule r : rules) {
            array.put(new JSONObject().put("id", r.id).put("name", r.name).put("enabled", r.enabled)
                    .put("days", r.days).put("start", r.startMinute).put("end", r.endMinute)
                    .put("mode", r.mode.name()).put("confirm", r.confirmationMinutes).put("repeat", r.repeatMinutes));
        }
        return array.toString();
    }
}
