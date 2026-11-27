/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.*;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig;

/** Reuses one native one-shot alarm. Never deletes alarms or infers dismissal from enabled=false. */
final class XiaomiWakeGuardClock {
    static final String OWNER = "xiaomi_wake_guard_clock_owner";
    static final String EDIT = "xiaomi_wake_guard_clock_edit";
    static final String DRAFT = "xiaomi_wake_guard_clock_draft";
    private final SharedPreferences prefs;
    private final Consumer<XiaomiProto.Command> send;
    private final Supplier<ZonedDateTime> now;
    private final Runnable completed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean waiting, verifying;
    private int mutation;
    private AlarmInfo expected;
    private final Runnable timeout = () -> finish("failed");

    XiaomiWakeGuardClock(SharedPreferences prefs, Consumer<XiaomiProto.Command> send,
                        Supplier<ZonedDateTime> now, Runnable completed) {
        this.prefs=prefs; this.send=send; this.now=now; this.completed=completed;
    }
    synchronized boolean isBusy() { return waiting; }
    synchronized boolean request() {
        if (waiting) return false;
        waiting=true; verifying=false; expected=null;
        status("pending"); query(); handler.postDelayed(timeout,10_000); return true;
    }
    private void query() { send.accept(XiaomiProto.Command.newBuilder().setType(17).setSubtype(0).build()); }
    private void status(String value) { prefs.edit().putString(XiaomiWakeGuardConfig.PREF_RING_STATUS,value).apply(); }
    private static AlarmDetails identity(AlarmDetails details) {
        return details.toBuilder().clearEnabled().clearNextRingTimestamp().build();
    }
    private AlarmInfo owner() {
        try { return AlarmInfo.parseFrom(Base64.decode(prefs.getString(OWNER,""),Base64.DEFAULT)); }
        catch (Exception e) { return null; }
    }
    private void own(AlarmInfo alarm) {
        prefs.edit().putString(OWNER,Base64.encodeToString(alarm.toByteArray(),Base64.NO_WRAP)).remove(DRAFT).remove(EDIT).apply();
    }
    private static boolean same(AlarmInfo a, AlarmInfo b) {
        return a!=null && b!=null && a.hasId() && b.hasId() && a.getId()==b.getId()
                && a.hasAlarmDetails() && b.hasAlarmDetails()
                && identity(a.getAlarmDetails()).equals(identity(b.getAlarmDetails()));
    }
    synchronized void onAlarms(Alarms alarms) {
        for (AlarmInfo alarm:alarms.getAlarmList()) {
            if (!alarm.hasId() || !alarm.hasAlarmDetails() || !alarm.getAlarmDetails().hasTime()) {
                if (waiting) finish("failed"); return;
            }
        }
        if (prefs.contains(EDIT)) {
            try {
                AlarmInfo edit=AlarmInfo.parseFrom(Base64.decode(prefs.getString(EDIT,""),Base64.DEFAULT));
                for (AlarmInfo alarm:alarms.getAlarmList()) if (same(edit,alarm)) { own(alarm); break; }
            } catch (Exception ignored) { }
        }
        // Recover a create whose ACK/readback was interrupted. Never blindly allocate a second slot.
        if (prefs.contains(DRAFT)) {
            try {
                JSONObject draft=new JSONObject(prefs.getString(DRAFT,""));
                JSONArray before=draft.getJSONArray("before"); List<AlarmInfo> matches=new ArrayList<>();
                for (AlarmInfo alarm:alarms.getAlarmList()) {
                    boolean existed=false;
                    for (int i=0;i<before.length();i++) if (before.getInt(i)==alarm.getId()) existed=true;
                    AlarmDetails d=alarm.getAlarmDetails();
                    if (!existed && d.hasTime() && d.getTime().getHour()==draft.getInt("hour")
                            && d.getTime().getMinute()==draft.getInt("minute") && d.getRepeatMode()==0
                            && d.getSmart()==2) matches.add(alarm);
                }
                if (matches.size()==1) own(matches.get(0));
                else { if (waiting) finish("failed"); return; }
            } catch (Exception e) { if (waiting) finish("failed"); return; }
        }
        if (!waiting) return;
        if (verifying) {
            for (AlarmInfo alarm:alarms.getAlarmList()) {
                if ((expected==null ? same(owner(),alarm) : same(expected,alarm))
                        && alarm.getAlarmDetails().getEnabled()) { own(alarm); finish("scheduled"); return; }
            }
            finish("failed"); return;
        }
        AlarmInfo saved=owner(), existing=null;
        for (AlarmInfo alarm:alarms.getAlarmList()) if (same(saved,alarm)) existing=alarm;
        if (saved!=null && existing==null) prefs.edit().remove(OWNER).apply(); // user edited/deleted it
        for (AlarmInfo alarm:alarms.getAlarmList()) {
            if (same(existing,alarm)) continue;
            int minute=now.get().getHour()*60+now.get().getMinute();
            int delta=Math.floorMod(alarm.getAlarmDetails().getTime().getHour()*60
                    +alarm.getAlarmDetails().getTime().getMinute()-minute+720,1440)-720;
            if (Math.abs(delta)<=2) { finish("busy"); return; }
        }
        if (existing==null && alarms.hasMaxAlarms() && alarms.getAlarmCount()>=alarms.getMaxAlarms()) {
            finish("full"); return;
        }
        ZonedDateTime target=now.get().plusMinutes(1).withSecond(0).withNano(0);
        AlarmDetails details=AlarmDetails.newBuilder().setTime(WearCommon.HourMinute.newBuilder()
                .setHour(target.getHour()).setMinute(target.getMinute()))
                .setRepeatMode(0).setEnabled(true).setSmart(2).build();
        Schedule.Builder schedule=Schedule.newBuilder();
        if (existing!=null) {
            // Preserve the watch's own label and extra fields.
            details=existing.getAlarmDetails().toBuilder().setTime(details.getTime()).setEnabled(true)
                    .setRepeatMode(0).clearRepeatFlags().setSmart(2).clearNextRingTimestamp().build();
            expected=AlarmInfo.newBuilder().setId(existing.getId()).setAlarmDetails(details).build();
            prefs.edit().putString(EDIT,Base64.encodeToString(expected.toByteArray(),Base64.NO_WRAP)).apply();
            schedule.setEditAlarm(expected); mutation=2;
        } else {
            try {
                JSONArray before=new JSONArray();
                for (AlarmInfo alarm:alarms.getAlarmList()) before.put(alarm.getId());
                JSONObject draft=new JSONObject().put("before",before).put("hour",target.getHour()).put("minute",target.getMinute());
                prefs.edit().putString(DRAFT,draft.toString()).apply();
            } catch (Exception e) { finish("failed"); return; }
            schedule.setCreateAlarm(details.toBuilder().setLabel("保持清醒")); mutation=1;
        }
        verifying=true;
        send.accept(XiaomiProto.Command.newBuilder().setType(17).setSubtype(mutation).setSchedule(schedule).build());
    }
    synchronized boolean onAck(XiaomiProto.Command cmd) {
        if (!waiting || !verifying || cmd.getSubtype()!=mutation) return false;
        if ((cmd.hasStatus() && cmd.getStatus()!=0) || (cmd.hasSchedule() && cmd.getSchedule().hasClockError())) {
            // An explicit error means no create happened; a missing ACK keeps the draft for recovery.
            if (mutation==1) prefs.edit().remove(DRAFT).remove(EDIT).apply();
            finish("failed");
        } else query();
        return true;
    }
    private synchronized void finish(String result) {
        if (!waiting) return;
        waiting=false; verifying=false; expected=null; handler.removeCallbacks(timeout); status(result); completed.run();
    }
    synchronized void disconnect() {
        waiting=false; verifying=false; expected=null; handler.removeCallbacks(timeout);
        // Retain ownership/draft, but never replay a queued alert on reconnect.
    }
}
