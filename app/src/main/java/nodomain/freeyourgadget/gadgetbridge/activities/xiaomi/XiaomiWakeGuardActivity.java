/* SPDX-License-Identifier: AGPL-3.0-or-later */

package nodomain.freeyourgadget.gadgetbridge.activities.xiaomi;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.json.JSONException;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.Rule;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.Mode;

public class XiaomiWakeGuardActivity extends AbstractSettingsActivityV2 {
    @Override
    protected PreferenceFragmentCompat newFragment() {
        return new RulesFragment();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE) == null) { finish(); return; }
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    public static class RulesFragment extends AbstractPreferenceFragment {
        private GBDevice device;
        private SharedPreferences prefs;
        private final SharedPreferences.OnSharedPreferenceChangeListener listener = (shared, key) -> {
            if (XiaomiWakeGuardConfig.PREF_STATUS.equals(key) || XiaomiWakeGuardConfig.PREF_SAMPLE_AT.equals(key)) {
                updateStatus();
            }
            if (XiaomiWakeGuardConfig.PREF_VIBRATION_STATUS.equals(key)) updateVibrationStatus();
            if (XiaomiWakeGuardConfig.PREF_RING_STATUS.equals(key)) updateRingStatus();
        };

        @Override
        public void onCreatePreferences(Bundle state, String rootKey) {
            device = requireActivity().getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
            prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
            render();
        }

        @Override public void onResume() {
            render();
            super.onResume();
            prefs.registerOnSharedPreferenceChangeListener(listener);
            updateStatus();
            updateVibrationStatus();
            updateRingStatus();
        }

        @Override public void onPause() {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
            super.onPause();
        }

        private Preference row(int title, CharSequence summary) {
            Preference p = new Preference(requireContext());
            p.setTitle(title);
            p.setSummary(summary);
            p.setIconSpaceReserved(false);
            return p;
        }

        private void render() {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            screen.setTitle(R.string.wake_guard_title);
            setPreferenceScreen(screen);
            SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(requireContext());
            enabled.setTitle(R.string.wake_guard_enabled);
            enabled.setIconSpaceReserved(false);
            enabled.setPersistent(false);
            enabled.setChecked(prefs.getBoolean(XiaomiWakeGuardConfig.PREF_ENABLED, false));
            enabled.setOnPreferenceChangeListener((preference, value) -> {
                prefs.edit().putBoolean(XiaomiWakeGuardConfig.PREF_ENABLED, (Boolean) value).apply();
                GBApplication.deviceService(device).onSendConfiguration(XiaomiWakeGuardConfig.PREF_ENABLED);
                updateStatus();
                return true;
            });
            screen.addPreference(enabled);
            Preference status = row(R.string.wake_guard_status, "");
            status.setKey(XiaomiWakeGuardConfig.PREF_STATUS);
            status.setSelectable(false);
            screen.addPreference(status);
            Preference about = row(R.string.wake_guard_help,
                    getString(R.string.wake_guard_timezone, ZoneId.systemDefault().getId()));
            about.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.wake_guard_help)
                        .setMessage(getString(R.string.wake_guard_about) + "\n\n" + getString(R.string.wake_guard_sound_note))
                        .setPositiveButton(android.R.string.ok, null).show();
                return true;
            });
            screen.addPreference(about);
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(R.string.wake_guard_rules);
            screen.addPreference(category);
            List<Rule> rules;
            try { rules = readRules(); }
            catch (JSONException e) {
                Preference error = row(R.string.wake_guard_invalid, null);
                error.setSelectable(false);
                category.addPreference(error);
                updateStatus();
                return;
            }
            String[] weekdays = getResources().getStringArray(R.array.wake_guard_weekdays);
            for (Rule rule : rules) {
                List<String> days = new ArrayList<>();
                for (int d = 0; d < 7; d++) if ((rule.days & (1 << d)) != 0) days.add(weekdays[d]);
                Preference p = new Preference(requireContext());
                p.setIconSpaceReserved(false);
                p.setTitle((rule.enabled ? "" : getString(R.string.wake_guard_paused)) + rule.name);
                p.setSummary(getString(R.string.wake_guard_rule_summary, String.join(" ", days),
                        time(rule.startMinute), time(rule.endMinute),
                        rule.startMinute > rule.endMinute ? getString(R.string.wake_guard_next_day) : "",
                        getString(rule.mode == Mode.RING ? R.string.wake_guard_ring : R.string.wake_guard_vibrate),
                        rule.confirmationMinutes, rule.repeatMinutes));
                p.setOnPreferenceClickListener(preference -> { edit(rule); return true; });
                category.addPreference(p);
            }
            Preference add = row(R.string.wake_guard_add, rules.isEmpty() ? getString(R.string.wake_guard_empty) : null);
            add.setOnPreferenceClickListener(p -> {
                if (rules.size() >= XiaomiWakeGuardConfig.MAX_RULES) toast(R.string.wake_guard_limit);
                else edit(null);
                return true;
            });
            category.addPreference(add);
            PreferenceCategory tests = new PreferenceCategory(requireContext());
            tests.setTitle(R.string.wake_guard_test);
            screen.addPreference(tests);
            addTest(tests, R.string.wake_guard_test_vibrate, XiaomiWakeGuardConfig.TEST_VIBRATE);
            addTest(tests, R.string.wake_guard_test_ring, XiaomiWakeGuardConfig.TEST_RING);
            updateStatus();
        }

        private void addTest(androidx.preference.PreferenceGroup screen, int label, String action) {
            Preference test = row(label, null);
            test.setKey(action);
            test.setPersistent(false);
            test.setOnPreferenceClickListener(preference -> {
                GBApplication.deviceService(device).onSendConfiguration(action);
                return true;
            });
            screen.addPreference(test);
            if (XiaomiWakeGuardConfig.TEST_VIBRATE.equals(action)) updateVibrationStatus();
            if (XiaomiWakeGuardConfig.TEST_RING.equals(action)) updateRingStatus();
        }

        private void updateVibrationStatus() {
            Preference test = findPreference(XiaomiWakeGuardConfig.TEST_VIBRATE);
            if (test == null) return;
            switch (prefs.getString(XiaomiWakeGuardConfig.PREF_VIBRATION_STATUS, "")) {
                case "native_sent": test.setSummary(R.string.wake_guard_vibration_native); break;
                case "busy": test.setSummary(R.string.wake_guard_alert_busy); break;
                default: test.setSummary(R.string.wake_guard_vibration_note);
            }
        }

        private void updateRingStatus() {
            Preference test = findPreference(XiaomiWakeGuardConfig.TEST_RING);
            if (test == null) return;
            switch (prefs.getString(XiaomiWakeGuardConfig.PREF_RING_STATUS, "")) {
                case "pending": test.setSummary(R.string.wake_guard_ring_pending); break;
                case "scheduled": test.setSummary(R.string.wake_guard_ring_sent); break;
                case "full": test.setSummary(R.string.wake_guard_ring_full); break;
                case "failed": test.setSummary(R.string.wake_guard_ring_failed); break;
                case "busy": test.setSummary(R.string.wake_guard_alert_busy); break;
                default: test.setSummary(R.string.wake_guard_ring_note);
            }
        }

        private void updateStatus() {
            Preference status = findPreference(XiaomiWakeGuardConfig.PREF_STATUS);
            if (status == null) return;
            String value = prefs.getBoolean(XiaomiWakeGuardConfig.PREF_ENABLED, false)
                    ? prefs.getString(XiaomiWakeGuardConfig.PREF_STATUS, "stale") : "disabled";
            if (prefs.getBoolean(XiaomiWakeGuardConfig.PREF_ENABLED, false)
                    && !device.isInitialized()) value = "disconnected";
            else if ("awake".equals(value) || "suspected".equals(value)) {
                long age = java.lang.System.currentTimeMillis() - prefs.getLong(XiaomiWakeGuardConfig.PREF_SAMPLE_AT, 0);
                if (age < 0 || age > nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardEngine.MAX_SAMPLE_AGE_MS) value = "stale";
            }
            int resource;
            switch (value) {
                case "disabled": resource = R.string.wake_guard_state_disabled; break;
                case "outside": resource = R.string.wake_guard_state_outside; break;
                case "not_worn": resource = R.string.wake_guard_state_not_worn; break;
                case "charging": resource = R.string.wake_guard_state_charging; break;
                case "suspected": resource = R.string.wake_guard_state_suspected; break;
                case "awake": resource = R.string.wake_guard_state_awake; break;
                case "disconnected": resource = R.string.wake_guard_state_disconnected; break;
                case "invalid": resource = R.string.wake_guard_invalid; break;
                default: resource = R.string.wake_guard_state_stale;
            }
            status.setSummary(resource);
        }

        private List<Rule> readRules() throws JSONException {
            return XiaomiWakeGuardConfig.parseRules(prefs.getString(XiaomiWakeGuardConfig.PREF_RULES, "[]"));
        }

        private void toast(int id) { Toast.makeText(requireContext(), id, Toast.LENGTH_LONG).show(); }
        private String time(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }

        private void edit(Rule rule) {
            RuleFragment editor = new RuleFragment();
            Bundle args = new Bundle();
            if (rule != null) args.putString("id", rule.id);
            editor.setArguments(args);
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, editor)
                    .addToBackStack("wake_guard_rule").commit();
        }
    }

    /** Uses the same Pixel settings rows, Material dialogs and screen shell as the rest of the app. */
    public static class RuleFragment extends AbstractPreferenceFragment {
        private static final int MENU_SAVE = 2801;
        private GBDevice device;
        private SharedPreferences prefs;
        private Bundle draft;
        private String id;
        private boolean existing;

        @Override
        public void onCreatePreferences(Bundle state, String rootKey) {
            device = requireActivity().getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
            prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
            id = requireArguments().getString("id");
            existing = id != null;
            if (state != null) {
                draft = state.getBundle("draft");
                id = state.getString("id");
            }
            if (draft == null) {
                draft = new Bundle();
                Rule selected = null;
                try {
                    for (Rule rule : XiaomiWakeGuardConfig.parseRules(prefs.getString(XiaomiWakeGuardConfig.PREF_RULES, "[]"))) {
                        if (rule.id.equals(id)) selected = rule;
                    }
                } catch (JSONException e) { fail(R.string.wake_guard_invalid); return; }
                if (existing && selected == null) { fail(R.string.wake_guard_invalid); return; }
                if (!existing) id = UUID.randomUUID().toString();
                draft.putString("name", selected == null ? "" : selected.name);
                draft.putBoolean("enabled", selected == null || selected.enabled);
                draft.putInt("days", selected == null ? 31 : selected.days);
                draft.putInt("start", selected == null ? 480 : selected.startMinute);
                draft.putInt("end", selected == null ? 720 : selected.endMinute);
                draft.putString("mode", selected == null ? Mode.VIBRATE.name() : selected.mode.name());
                draft.putInt("confirm", selected == null ? 1 : selected.confirmationMinutes);
                draft.putInt("repeat", selected == null ? 5 : selected.repeatMinutes);
            }
            render();
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle state) {
            super.onSaveInstanceState(state);
            state.putBundle("draft", draft);
            state.putString("id", id);
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle state) {
            super.onViewCreated(view, state);
            for (String key : new String[]{"start", "end"}) {
                androidx.fragment.app.Fragment restored = getChildFragmentManager().findFragmentByTag("wake_guard_time_" + key);
                if (restored instanceof MaterialTimePicker) bindTimePicker((MaterialTimePicker) restored, key);
            }
        }

        private <T extends Preference> T add(PreferenceScreen screen, T preference, String key, int title) {
            preference.setKey("wake_guard_draft_" + key);
            preference.setTitle(title);
            preference.setPersistent(false);
            preference.setIconSpaceReserved(false);
            screen.addPreference(preference);
            return preference;
        }

        private void render() {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            screen.setTitle(existing ? R.string.wake_guard_edit : R.string.wake_guard_add);
            setPreferenceScreen(screen);
            EditTextPreference name = add(screen, new EditTextPreference(requireContext()), "name", R.string.wake_guard_name);
            name.setText(draft.getString("name"));
            name.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            name.setOnBindEditTextListener(edit -> {
                edit.setSingleLine(true);
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
            });
            name.setOnPreferenceChangeListener((p, value) -> { draft.putString("name", (String) value); return true; });
            SwitchPreferenceCompat enabled = add(screen, new SwitchPreferenceCompat(requireContext()), "enabled", R.string.wake_guard_rule_enabled);
            enabled.setChecked(draft.getBoolean("enabled"));
            enabled.setOnPreferenceChangeListener((p, value) -> { draft.putBoolean("enabled", (Boolean) value); return true; });
            MultiSelectListPreference days = add(screen, new MultiSelectListPreference(requireContext()), "days", R.string.wake_guard_days);
            String[] labels = getResources().getStringArray(R.array.wake_guard_weekdays);
            String[] values = {"0", "1", "2", "3", "4", "5", "6"};
            days.setEntries(labels);
            days.setEntryValues(values);
            Set<String> selected = new HashSet<>();
            for (int i = 0; i < 7; i++) if ((draft.getInt("days") & (1 << i)) != 0) selected.add(values[i]);
            days.setValues(selected);
            days.setSummaryProvider(preference -> {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < 7; i++) if (days.getValues().contains(values[i])) names.add(labels[i]);
                return String.join(" ", names);
            });
            days.setOnPreferenceChangeListener((p, value) -> {
                Set<?> checked = (Set<?>) value;
                if (checked.isEmpty()) { toast(R.string.wake_guard_validation); return false; }
                int mask = 0;
                for (int i = 0; i < 7; i++) if (checked.contains(values[i])) mask |= 1 << i;
                draft.putInt("days", mask);
                return true;
            });
            timeRow(screen, "start", R.string.wake_guard_start);
            timeRow(screen, "end", R.string.wake_guard_end);
            ListPreference mode = add(screen, new ListPreference(requireContext()), "mode", R.string.wake_guard_mode);
            mode.setEntries(new String[]{getString(R.string.wake_guard_vibrate), getString(R.string.wake_guard_ring)});
            mode.setEntryValues(new String[]{Mode.VIBRATE.name(), Mode.RING.name()});
            mode.setValue(draft.getString("mode"));
            mode.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            mode.setOnPreferenceChangeListener((p, value) -> { draft.putString("mode", (String) value); return true; });
            minutesRow(screen, "confirm", R.string.wake_guard_confirm, 30);
            minutesRow(screen, "repeat", R.string.wake_guard_repeat, 60);
            Preference timezone = add(screen, new Preference(requireContext()), "timezone", R.string.pref_title_datetime_timezone);
            timezone.setSummary(ZoneId.systemDefault().getId());
            timezone.setSelectable(false);
            if (existing) {
                Preference delete = add(screen, new Preference(requireContext()), "delete", R.string.delete);
                delete.setOnPreferenceClickListener(p -> {
                    new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.wake_guard_delete)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.delete, (dialog, which) -> save(true)).show();
                    return true;
                });
            }
        }

        private void timeRow(PreferenceScreen screen, String key, int title) {
            Preference row = add(screen, new Preference(requireContext()), key, title);
            row.setSummary(time(draft.getInt(key)));
            row.setOnPreferenceClickListener(preference -> {
                int minute = draft.getInt(key);
                MaterialTimePicker picker = new MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H)
                        .setHour(minute / 60).setMinute(minute % 60).setTitleText(title).build();
                bindTimePicker(picker, key);
                picker.show(getChildFragmentManager(), "wake_guard_time_" + key);
                return true;
            });
        }

        private void bindTimePicker(MaterialTimePicker picker, String key) {
            picker.clearOnPositiveButtonClickListeners();
            picker.addOnPositiveButtonClickListener(view -> {
                draft.putInt(key, picker.getHour() * 60 + picker.getMinute());
                Preference row = findPreference("wake_guard_draft_" + key);
                if (row != null) row.setSummary(time(draft.getInt(key)));
            });
        }

        private void minutesRow(PreferenceScreen screen, String key, int title, int max) {
            ListPreference row = add(screen, new ListPreference(requireContext()), key, title);
            String[] entries = new String[max], values = new String[max];
            for (int i = 0; i < max; i++) {
                values[i] = Integer.toString(i + 1);
                entries[i] = getString(R.string.wake_guard_minutes, i + 1);
            }
            row.setEntries(entries); row.setEntryValues(values);
            row.setValue(Integer.toString(draft.getInt(key)));
            row.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            row.setOnPreferenceChangeListener((p, value) -> { draft.putInt(key, Integer.parseInt((String) value)); return true; });
        }

        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
            super.onCreateMenu(menu, inflater);
            if (draft == null || !draft.containsKey("name")) return;
            menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, R.string.save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
            if (item.getItemId() == MENU_SAVE) { save(false); return true; }
            return super.onMenuItemSelected(item);
        }

        private void save(boolean delete) {
            try {
                List<Rule> rules = XiaomiWakeGuardConfig.parseRules(prefs.getString(XiaomiWakeGuardConfig.PREF_RULES, "[]"));
                int index = -1;
                for (int i = 0; i < rules.size(); i++) if (rules.get(i).id.equals(id)) index = i;
                if (delete) {
                    if (index >= 0) rules.remove(index);
                } else {
                    Rule replacement = new Rule(id, draft.getString("name"), draft.getBoolean("enabled"), draft.getInt("days"),
                            draft.getInt("start"), draft.getInt("end"), Mode.valueOf(draft.getString("mode")),
                            draft.getInt("confirm"), draft.getInt("repeat"));
                    if (existing && index < 0) { toast(R.string.wake_guard_invalid); return; }
                    if (index >= 0) rules.set(index, replacement);
                    else if (rules.size() < XiaomiWakeGuardConfig.MAX_RULES) rules.add(replacement);
                    else { toast(R.string.wake_guard_limit); return; }
                }
                prefs.edit().putString(XiaomiWakeGuardConfig.PREF_RULES, XiaomiWakeGuardConfig.serializeRules(rules)).apply();
                GBApplication.deviceService(device).onSendConfiguration(XiaomiWakeGuardConfig.PREF_RULES);
                getParentFragmentManager().popBackStack();
            } catch (JSONException e) { toast(R.string.wake_guard_invalid); }
            catch (IllegalArgumentException e) { toast(R.string.wake_guard_validation); }
        }

        private void fail(int message) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            screen.setTitle(R.string.wake_guard_title);
            setPreferenceScreen(screen);
            Preference error = add(screen, new Preference(requireContext()), "error", message);
            error.setSelectable(false);
        }
        private void toast(int message) { Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show(); }
        private String time(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
    }
}
