/* SPDX-License-Identifier: AGPL-3.0-or-later */

package nodomain.freeyourgadget.gadgetbridge.activities.xiaomi;

import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.Rule;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.Mode;

public class XiaomiWakeGuardActivity extends AbstractGBActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE) == null) { finish(); return; }
        setContentView(R.layout.activity_xiaomi_wake_guard);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) getSupportFragmentManager().beginTransaction()
                .replace(R.id.wake_guard_preferences, new RulesFragment()).commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    public static class RulesFragment extends PreferenceFragmentCompat {
        private GBDevice device;
        private SharedPreferences prefs;
        private final SharedPreferences.OnSharedPreferenceChangeListener listener = (shared, key) -> {
            if (XiaomiWakeGuardConfig.PREF_STATUS.equals(key) || XiaomiWakeGuardConfig.PREF_SAMPLE_AT.equals(key)) {
                updateStatus();
            }
        };

        @Override
        public void onCreatePreferences(Bundle state, String rootKey) {
            device = requireActivity().getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
            prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
            render();
        }

        @Override public void onResume() {
            super.onResume();
            prefs.registerOnSharedPreferenceChangeListener(listener);
            updateStatus();
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
            Preference about = row(R.string.wake_guard_title, getString(R.string.wake_guard_about)
                    + "\n" + getString(R.string.wake_guard_timezone, ZoneId.systemDefault().getId()));
            about.setSelectable(false);
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
            Preference note = row(R.string.wake_guard_mode, getString(R.string.wake_guard_sound_note));
            note.setSelectable(false);
            screen.addPreference(note);
            addTest(screen, R.string.wake_guard_test_vibrate, XiaomiWakeGuardConfig.TEST_VIBRATE);
            addTest(screen, R.string.wake_guard_test_ring, XiaomiWakeGuardConfig.TEST_RING);
            addTest(screen, R.string.wake_guard_stop_ring, XiaomiWakeGuardConfig.STOP_RING);
            updateStatus();
        }

        private void addTest(PreferenceScreen screen, int label, String action) {
            Preference test = row(label, null);
            test.setOnPreferenceClickListener(preference -> {
                GBApplication.deviceService(device).onSendConfiguration(action);
                return true;
            });
            screen.addPreference(test);
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
                if (age < 0 || age > 90_000) value = "stale";
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

        private void save(Rule previous, Rule replacement) {
            try {
                List<Rule> rules = readRules();
                if (previous == null) rules.add(replacement);
                else {
                    int index = -1;
                    for (int i = 0; i < rules.size(); i++) if (rules.get(i).id.equals(previous.id)) index = i;
                    if (index < 0) return;
                    if (replacement == null) rules.remove(index); else rules.set(index, replacement);
                }
                prefs.edit().putString(XiaomiWakeGuardConfig.PREF_RULES, XiaomiWakeGuardConfig.serializeRules(rules)).apply();
                GBApplication.deviceService(device).onSendConfiguration(XiaomiWakeGuardConfig.PREF_RULES);
                render();
            } catch (JSONException e) { toast(R.string.wake_guard_invalid); }
        }

        private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
        private void toast(int id) { Toast.makeText(requireContext(), id, Toast.LENGTH_LONG).show(); }
        private String time(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
        private void label(LinearLayout box, int resource) {
            TextView text = new TextView(requireContext());
            text.setText(resource);
            text.setPadding(0, dp(16), 0, dp(4));
            box.addView(text);
        }
        private EditText input(LinearLayout box, int resource, String value, boolean numeric) {
            label(box, resource);
            EditText field = new EditText(requireContext());
            field.setSingleLine(true);
            field.setText(value);
            field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
            field.setContentDescription(getString(resource));
            box.addView(field, new LinearLayout.LayoutParams(-1, -2));
            return field;
        }

        private void edit(Rule rule) {
            LinearLayout box = new LinearLayout(requireContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(8), dp(24), dp(24));
            EditText name = input(box, R.string.wake_guard_name, rule == null ? "" : rule.name, false);
            SwitchCompat enabled = new SwitchCompat(requireContext());
            enabled.setText(R.string.wake_guard_rule_enabled);
            enabled.setChecked(rule == null || rule.enabled);
            box.addView(enabled, new LinearLayout.LayoutParams(-1, dp(48)));
            label(box, R.string.wake_guard_days);
            ChipGroup dayGroup = new ChipGroup(requireContext());
            Chip[] days = new Chip[7];
            String[] labels = getResources().getStringArray(R.array.wake_guard_weekdays);
            for (int i = 0; i < 7; i++) {
                Chip chip = new Chip(requireContext());
                chip.setText(labels[i]); chip.setCheckable(true);
                chip.setChecked(((rule == null ? 31 : rule.days) & (1 << i)) != 0);
                dayGroup.addView(chip); days[i] = chip;
            }
            box.addView(dayGroup);
            int[] times = {rule == null ? 8 * 60 : rule.startMinute, rule == null ? 12 * 60 : rule.endMinute};
            for (int i = 0; i < 2; i++) {
                final int index = i;
                label(box, i == 0 ? R.string.wake_guard_start : R.string.wake_guard_end);
                Button button = new Button(requireContext());
                button.setText(time(times[i]));
                button.setOnClickListener(view -> new TimePickerDialog(requireContext(), (picker, h, m) -> {
                    times[index] = h * 60 + m; button.setText(time(times[index]));
                }, times[index] / 60, times[index] % 60, true).show());
                box.addView(button, new LinearLayout.LayoutParams(-1, -2));
            }
            label(box, R.string.wake_guard_mode);
            Spinner mode = new Spinner(requireContext());
            ArrayAdapter<String> choices = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item,
                    new String[]{getString(R.string.wake_guard_vibrate), getString(R.string.wake_guard_ring)});
            choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mode.setAdapter(choices);
            mode.setSelection(rule != null && rule.mode == Mode.RING ? 1 : 0);
            box.addView(mode, new LinearLayout.LayoutParams(-1, dp(48)));
            EditText confirm = input(box, R.string.wake_guard_confirm,
                    Integer.toString(rule == null ? 1 : rule.confirmationMinutes), true);
            EditText repeat = input(box, R.string.wake_guard_repeat,
                    Integer.toString(rule == null ? 5 : rule.repeatMinutes), true);
            ScrollView scroll = new ScrollView(requireContext()); scroll.addView(box);
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(rule == null ? R.string.wake_guard_add : R.string.wake_guard_title)
                    .setView(scroll).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.ok, null);
            if (rule != null) builder.setNeutralButton(R.string.delete, (dialog, which) ->
                    new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.wake_guard_delete)
                            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.delete,
                                    (confirmation, button) -> save(rule, null)).show());
            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int mask = 0;
                for (int i = 0; i < 7; i++) if (days[i].isChecked()) mask |= 1 << i;
                try {
                    Rule replacement = new Rule(rule == null ? UUID.randomUUID().toString() : rule.id,
                            name.getText().toString(), enabled.isChecked(), mask, times[0], times[1],
                            mode.getSelectedItemPosition() == 1 ? Mode.RING : Mode.VIBRATE,
                            Integer.parseInt(confirm.getText().toString()), Integer.parseInt(repeat.getText().toString()));
                    save(rule, replacement); dialog.dismiss();
                } catch (IllegalArgumentException e) { toast(R.string.wake_guard_validation); }
            }));
            dialog.show();
        }
    }
}
