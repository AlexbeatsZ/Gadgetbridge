/* Copyright (C) 2026 Gadgetbridge contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic;

import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reads only a Gemini response container, including unnamed views inside action cards. */
final class AssistantResponseReader {
    private static final int MAX_VIEWS = 512;
    private static final int MAX_CHARS = 4000;

    private AssistantResponseReader() {
    }

    static boolean isResponseContainer(final String name) {
        return "assistant_robin_floaty_message_content".equals(name)
                || "assistant_robin_message_content".equals(name);
    }

    static boolean isExcluded(final String name) {
        return name.startsWith("assistant_robin_message_content_actions")
                || name.contains("response_footer")
                || name.contains("progress_bar")
                || name.contains("generic_status")
                || name.contains("loading_tool")
                || name.endsWith("tool_name");
    }

    static String read(final View root) {
        if (!root.isShown()) {
            return "";
        }
        final Set<String> texts = new LinkedHashSet<>();
        final ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        int chars = 0;
        while (!pending.isEmpty() && visited++ < MAX_VIEWS && chars < MAX_CHARS) {
            final View view = pending.removeFirst();
            if (view.getVisibility() != View.VISIBLE || isExcluded(resourceName(view))) {
                continue;
            }
            if (view instanceof TextView) {
                chars += add(texts, ((TextView) view).getText());
            } else {
                // Rendered cards also expose labels through accessibility delegates on ordinary Views.
                final AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
                if (node != null) {
                    chars += add(texts, node.getText());
                    chars += add(texts, node.getContentDescription());
                    node.recycle();
                }
            }
            if (view instanceof ViewGroup) {
                final ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    pending.addFirst(group.getChildAt(i));
                }
            }
        }
        final String text = String.join("\n", texts);
        final int end = text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), MAX_CHARS));
        return text.substring(0, end);
    }

    private static int add(final Set<String> texts, final CharSequence value) {
        if (value == null) {
            return 0;
        }
        final String text = value.toString().trim();
        return !text.isEmpty() && texts.add(text) ? text.length() : 0;
    }

    private static String resourceName(final View view) {
        if (view.getId() != View.NO_ID) {
            try {
                return view.getResources().getResourceEntryName(view.getId());
            } catch (final android.content.res.Resources.NotFoundException ignored) {
                // Many card descendants have dynamically generated IDs.
            }
        }
        return "";
    }
}
