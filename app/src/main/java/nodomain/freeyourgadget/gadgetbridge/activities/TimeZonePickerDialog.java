/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.function.Consumer;

import nodomain.freeyourgadget.gadgetbridge.adapter.TimeZonePickerAdapter;
import nodomain.freeyourgadget.gadgetbridge.databinding.DialogTimezonePickerBinding;

/**
 * Searchable dialog to pick one of the available time zones.
 */
public final class TimeZonePickerDialog {
    private TimeZonePickerDialog() {
    }

    public static void show(final Activity activity,
                            @StringRes final int title,
                            @Nullable final String selectedId,
                            final Consumer<String> onSelected) {
        show(activity, title, selectedId, onSelected, 0, null);
    }

    /**
     * @param neutralButton  label for an optional neutral button, or 0 for none
     * @param onNeutral      invoked when the neutral button is clicked
     */
    public static void show(final Activity activity,
                            @StringRes final int title,
                            @Nullable final String selectedId,
                            final Consumer<String> onSelected,
                            @StringRes final int neutralButton,
                            @Nullable final Runnable onNeutral) {
        final DialogTimezonePickerBinding pickerBinding = DialogTimezonePickerBinding.inflate(activity.getLayoutInflater());
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(pickerBinding.getRoot())
            .setNegativeButton(android.R.string.cancel, null);
        if (neutralButton != 0) {
            builder.setNeutralButton(neutralButton, (d, which) -> {
                if (onNeutral != null) {
                    onNeutral.run();
                }
            });
        }
        final AlertDialog dialog = builder.create();

        final TimeZonePickerAdapter adapter = new TimeZonePickerAdapter(selectedId != null ? selectedId : "", timeZoneId -> {
            onSelected.accept(timeZoneId);
            dialog.dismiss();
        });
        pickerBinding.timezoneList.setLayoutManager(new LinearLayoutManager(activity));
        pickerBinding.timezoneList.setAdapter(adapter);

        new Thread(() -> {
            final List<TimeZonePickerAdapter.Entry> entries = TimeZonePickerAdapter.buildEntries();
            activity.runOnUiThread(() -> {
                adapter.setEntries(entries);
                final int selectedPosition = adapter.getSelectedPosition();
                if (selectedPosition != RecyclerView.NO_POSITION) {
                    pickerBinding.timezoneList.scrollToPosition(selectedPosition);
                }
            });
        }, "TimeZonePickerLoader").start();

        pickerBinding.timezoneSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            }

            @Override
            public void afterTextChanged(final Editable s) {
                adapter.filter(s.toString());
                pickerBinding.timezoneList.scrollToPosition(0);
            }
        });

        dialog.show();
    }
}
