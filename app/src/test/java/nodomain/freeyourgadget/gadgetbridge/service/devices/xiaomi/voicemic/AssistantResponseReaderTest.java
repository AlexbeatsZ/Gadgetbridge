package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {23, 35})
public class AssistantResponseReaderTest {
    private Activity activity;
    private LinearLayout response;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        response = new LinearLayout(activity);
        activity.setContentView(response);
    }

    @Test
    public void cardOnlyResponseIncludesUnnamedDescendantsInOrder() {
        final LinearLayout card = new LinearLayout(activity);
        response.addView(card);
        addText(card, "闹钟已设置");
        addText(card, "07:30");
        addText(card, "明天");
        assertEquals("闹钟已设置\n07:30\n明天", AssistantResponseReader.read(response));
    }

    @Test
    public void responseCombinesNarrativeAndCardWithoutDuplicateLabels() {
        addText(response, "已为你设置闹钟");
        final View label = new View(activity);
        label.setContentDescription("07:30");
        response.addView(label);
        addText(response, "07:30");
        assertEquals("已为你设置闹钟\n07:30", AssistantResponseReader.read(response));
    }

    @Test
    public void customCardAccessibilityLabelsAreRead() {
        final View card = new View(activity);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setText("计时器已启动");
                info.setContentDescription("10 分钟");
            }
        });
        response.addView(card);
        assertEquals("计时器已启动\n10 分钟", AssistantResponseReader.read(response));
    }

    @Test
    public void hiddenCardsAndTheirChildrenAreIgnored() {
        final LinearLayout oldCard = new LinearLayout(activity);
        response.addView(oldCard);
        addText(oldCard, "旧闹钟");
        oldCard.setVisibility(View.GONE);
        addText(response, "新闹钟");
        assertEquals("新闹钟", AssistantResponseReader.read(response));
        response.setVisibility(View.INVISIBLE);
        assertEquals("", AssistantResponseReader.read(response));
    }

    @Test
    public void recycledCardUsesItsCurrentContent() {
        final TextView time = addText(response, "07:30");
        assertEquals("07:30", AssistantResponseReader.read(response));
        time.setText("08:00");
        assertEquals("08:00", AssistantResponseReader.read(response));
        response.removeAllViews();
        assertEquals("", AssistantResponseReader.read(response));
    }

    @Test
    public void readsPlainAnswersAndPreservesFailureOrConfirmationText() {
        final TextView text = addText(response, "无法设置闹钟，请解锁手机");
        assertEquals("无法设置闹钟，请解锁手机", AssistantResponseReader.read(response));
        text.setText("是否要将闹钟设置为 07:30？");
        assertEquals("是否要将闹钟设置为 07:30？", AssistantResponseReader.read(response));
    }

    @Test
    public void scopesExcludeInputControlsFeedbackAndLoadingIndicators() {
        assertTrue(AssistantResponseReader.isResponseContainer("assistant_robin_floaty_message_content"));
        assertTrue(AssistantResponseReader.isResponseContainer("assistant_robin_message_content"));
        assertFalse(AssistantResponseReader.isResponseContainer("assistant_robin_floaty_single_line_query"));
        assertFalse(AssistantResponseReader.isResponseContainer("assistant_robin_content_frame"));
        assertTrue(AssistantResponseReader.isExcluded("assistant_robin_message_content_actions"));
        assertTrue(AssistantResponseReader.isExcluded("assistant_robin_message_content_generic_status_container"));
        assertFalse(AssistantResponseReader.isExcluded("assistant_fa_alarm_instance_field_time"));
    }

    private TextView addText(final LinearLayout parent, final String text) {
        final TextView view = new TextView(activity);
        view.setText(text);
        parent.addView(view);
        return view;
    }
}
