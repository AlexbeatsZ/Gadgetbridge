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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands the band's voice to the hook in the assistant's process. Only the assistant may read it, and only
 * while a session is handed over.
 */
public class VoiceMicProvider extends ContentProvider {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceMicProvider.class);

    @Override
    public boolean onCreate() {
        return true;
    }

    private boolean callerIsAssistant() {
        final String[] packages = getContext() != null
                ? getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid())
                : null;
        if (packages != null) {
            for (final String pkg : packages) {
                if (VoiceMicContract.ASSISTANT_PACKAGE.equals(pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        if (!callerIsAssistant() || !VoiceMicContract.PATH_STREAM.equals(uri.getLastPathSegment())) {
            throw new SecurityException("Not available to this caller");
        }
        try {
            final ParcelFileDescriptor stream = VoiceMicHub.getInstance().openStream();
            if (stream == null) {
                throw new FileNotFoundException("No voice session");
            }
            return stream;
        } catch (final IOException e) {
            LOG.error("Failed to open voice mic stream", e);
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Nullable
    @Override
    public Bundle call(@NonNull final String method, @Nullable final String arg, @Nullable final Bundle extras) {
        if (!callerIsAssistant()) {
            throw new SecurityException("Not available to this caller");
        }
        if (VoiceMicContract.METHOD_RECORDING_STOPPED.equals(method)) {
            LOG.info("Assistant stopped a fed recording");
            VoiceMicHub.getInstance().onRecordingStopped();
        } else if (VoiceMicContract.METHOD_REPORT.equals(method)) {
            LOG.info("Assistant voice mic report: {}", arg);
        } else if (VoiceMicContract.METHOD_ANSWER.equals(method)) {
            if (arg != null) {
                VoiceMicHub.getInstance().onAnswer(arg);
            }
        } else if (VoiceMicContract.METHOD_PANEL_TEXT.equals(method)) {
            final int sep = arg != null ? arg.indexOf(VoiceMicContract.SEPARATOR) : -1;
            if (sep > 0) {
                VoiceMicHub.getInstance().onPanelText(arg.substring(0, sep), arg.substring(sep + 1));
            }
        } else if (VoiceMicContract.METHOD_IS_ACTIVE.equals(method)) {
            final Bundle result = new Bundle();
            result.putBoolean(VoiceMicContract.KEY_ACTIVE, VoiceMicHub.getInstance().isActive());
            return result;
        }
        return null;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull final Uri uri, @Nullable final String[] projection, @Nullable final String selection,
                        @Nullable final String[] selectionArgs, @Nullable final String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull final Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull final Uri uri, @Nullable final ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull final Uri uri, @Nullable final String selection, @Nullable final String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull final Uri uri, @Nullable final ContentValues values, @Nullable final String selection,
                      @Nullable final String[] selectionArgs) {
        return 0;
    }
}
