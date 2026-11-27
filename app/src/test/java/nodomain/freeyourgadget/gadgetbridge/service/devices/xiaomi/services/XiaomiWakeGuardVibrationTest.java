/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.os.Looper;

import org.json.JSONObject;
import org.junit.Test;
import org.robolectric.Shadows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.*;

public class XiaomiWakeGuardVibrationTest extends TestBase {
    private final List<JSONObject> prepared = new ArrayList<>(), direct = new ArrayList<>();
    private final List<String> states = new ArrayList<>();
    private final long[] elapsed = {0};
    private final XiaomiWakeGuardVibration vibration = new XiaomiWakeGuardVibration(new XiaomiWakeGuardVibration.Transport() {
        public void prepare(byte[] payload, boolean force) { prepared.add(parse(payload)); }
        public void direct(byte[] payload) { direct.add(parse(payload)); }
        public void clearPending() { }
    }, states::add, () -> elapsed[0]);

    private JSONObject parse(byte[] bytes) {
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private String reply(String op) throws Exception {
        return new JSONObject().put("protocol", XiaomiWakeGuardVibration.PROTOCOL)
                .put("id", prepared.get(prepared.size() - 1).getString("id")).put("op", op).toString();
    }

    @Test public void onlyFreshReadyCommitsOnceAndExecutionIsAcknowledged() throws Exception {
        vibration.request();
        assertTrue(direct.isEmpty());
        vibration.receive(reply("executed"));
        assertEquals("pending", states.get(states.size() - 1));
        vibration.receive(reply("ready"));
        vibration.receive(reply("ready"));
        assertEquals(1, direct.size());
        assertEquals("vibrate", direct.get(0).getString("op"));
        vibration.receive(reply("executed"));
        assertEquals("ok", states.get(states.size() - 1));
        vibration.cancel();
        assertEquals(1, direct.size());
    }

    @Test public void expiryAndCancellationRejectLateReady() throws Exception {
        vibration.request();
        String ready = reply("ready");
        elapsed[0] = XiaomiWakeGuardVibration.TIMEOUT_MS;
        vibration.receive(ready);
        assertEquals("failed", states.get(states.size() - 1));
        assertTrue(direct.isEmpty());
        vibration.request();
        ready = reply("ready");
        vibration.cancel();
        vibration.receive(ready);
        assertTrue(direct.isEmpty());
    }

    @Test public void retryOnlyPreparesAndTimeoutReportsMissingOldOrOfflineApp() {
        vibration.request();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(2, prepared.size());
        assertTrue(direct.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
        assertEquals("failed", states.get(states.size() - 1));
    }

    @Test public void cancellationAfterCommitSendsCancelAndIgnoresOldAcknowledgement() throws Exception {
        vibration.request();
        String executed = reply("executed");
        vibration.receive(reply("ready"));
        vibration.cancel();
        assertEquals("cancel", direct.get(1).getString("op"));
        vibration.receive(executed);
        assertEquals("cancelled", states.get(states.size() - 1));
    }

    @Test public void namespacesAndRequestIdsAreIsolated() throws Exception {
        assertFalse(XiaomiWakeGuardVibration.isMessage("other.app", "{\"protocol\":\"" + XiaomiWakeGuardVibration.PROTOCOL + "\"}"));
        assertFalse(XiaomiWakeGuardVibration.isMessage(XiaomiWakeGuardVibration.APP, "{\"op\":\"view\"}"));
        vibration.request();
        String oldReady = reply("ready");
        vibration.request();
        vibration.receive(oldReady);
        assertTrue(direct.isEmpty());
    }

    @Test public void rpkServiceRoutesControlBeforeThirdPartyBroadcastAndPreservesHandshake() throws Exception {
        nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport support =
                org.mockito.Mockito.mock(nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport.class);
        org.mockito.Mockito.when(support.getDevice()).thenReturn(createDummyGDevice("00:11:22:33:44:66"));
        XiaomiWatchfaceService watchfaces = org.mockito.Mockito.mock(XiaomiWatchfaceService.class);
        org.mockito.Mockito.when(watchfaces.getInstalledFacesCache()).thenReturn(java.util.Collections.emptyList());
        org.mockito.Mockito.when(support.getWatchfaceService()).thenReturn(watchfaces);
        XiaomiRpkService service = new XiaomiRpkService(support);
        com.google.protobuf.ByteString sha = com.google.protobuf.ByteString.copyFromUtf8("test-sha");
        var app = XiaomiRpkService.rpkRef(XiaomiWakeGuardVibration.APP, sha);
        service.requestWakeGuardVibration();
        service.handleCommand(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(XiaomiRpkService.COMMAND_TYPE).setSubtype(XiaomiRpkService.CMD_RPK_LIST)
                .setRpk(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Rpk.newBuilder().setRpkList(
                        nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.RpkList.newBuilder().addRpkInfo(app))).build());
        service.handleCommand(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(XiaomiRpkService.COMMAND_TYPE).setSubtype(XiaomiRpkService.CMD_RPK_LAUNCHED)
                .setRpk(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Rpk.newBuilder().setRpkDel(app)).build());
        var commands = org.mockito.ArgumentCaptor.forClass(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.class);
        org.mockito.Mockito.verify(support, org.mockito.Mockito.atLeastOnce()).sendCommand(org.mockito.Mockito.anyString(), commands.capture());
        List<nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command> launchCommands = commands.getAllValues();
        int firstMessage = -1, firstConnected = -1;
        for (int i = 0; i < launchCommands.size(); i++) {
            if (firstMessage < 0 && launchCommands.get(i).getSubtype() == XiaomiRpkService.CMD_RPK_MESSAGE) firstMessage = i;
            if (firstConnected < 0 && launchCommands.get(i).getSubtype() == XiaomiRpkService.CMD_RPK_SESSION_END) firstConnected = i;
        }
        assertTrue(firstConnected >= 0 && firstConnected < firstMessage);
        JSONObject prepare = commands.getAllValues().stream().filter(c -> c.getSubtype() == XiaomiRpkService.CMD_RPK_MESSAGE)
                .map(c -> parse(c.getRpk().getRpkMessage().getPayload().toByteArray())).findFirst().orElseThrow();
        assertEquals("prepare", prepare.getString("op"));
        prepare.put("op", "ready");
        service.handleCommand(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(XiaomiRpkService.COMMAND_TYPE).setSubtype(XiaomiRpkService.CMD_RPK_WEAR_MESSAGE)
                .setRpk(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Rpk.newBuilder().setRpkMessage(
                        nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.RpkMessage.newBuilder().setApp(app)
                                .setPayload(com.google.protobuf.ByteString.copyFromUtf8(prepare.toString())))).build());
        commands = org.mockito.ArgumentCaptor.forClass(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.class);
        org.mockito.Mockito.verify(support, org.mockito.Mockito.atLeastOnce()).sendCommand(org.mockito.Mockito.anyString(), commands.capture());
        assertTrue(commands.getAllValues().stream().filter(c -> c.getSubtype() == XiaomiRpkService.CMD_RPK_MESSAGE)
                .anyMatch(c -> "vibrate".equals(parse(c.getRpk().getRpkMessage().getPayload().toByteArray()).optString("op"))));
        service.dispose();
    }
}
