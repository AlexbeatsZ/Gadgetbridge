package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class XiaomiNfcRelayBridgeTest {
    private static class FakeTransport implements XiaomiNfcRelayBridge.RelayTransport {
        final List<JSONObject> sent = new ArrayList<>();
        final List<String> sentDirect = new ArrayList<>();
        final List<Boolean> relaunches = new ArrayList<>();
        final List<String> setFaces = new ArrayList<>();
        final List<UUID> setFaceUuids = new ArrayList<>();
        final CountDownLatch latch;
        UUID activeFace = XiaomiWatchfaceService.toWatchfaceUUID("120917345464");
        boolean hasRelayFace = true;

        FakeTransport(final int expectedReplies) {
            this.latch = new CountDownLatch(expectedReplies);
        }

        @Override
        public synchronized void sendMessage(final String appId, final byte[] payload) {
            try {
                sent.add(new JSONObject(new String(payload, StandardCharsets.UTF_8)));
            } catch (final Exception e) {
                throw new AssertionError(e);
            } finally {
                latch.countDown();
            }
        }

        @Override
        public synchronized void sendDirectMessage(final String appId, final byte[] payload) {
            sentDirect.add(new String(payload, StandardCharsets.US_ASCII));
            latch.countDown();
        }

        @Override
        public synchronized void relaunchApp(final String appId) {
            relaunches.add(true);
            latch.countDown();
        }

        @Override
        public UUID getActiveWatchface() {
            return activeFace;
        }

        @Override
        public boolean hasWatchface(final String watchfaceId) {
            return hasRelayFace && XiaomiNfcRelayBridge.RELAY_WATCHFACE_ID.equals(watchfaceId);
        }

        @Override
        public UUID getFallbackWatchface(final String excludedWatchfaceId) {
            return XiaomiWatchfaceService.toWatchfaceUUID("120917345464");
        }

        @Override
        public boolean setWatchfaceAndWait(final String watchfaceId, final long timeoutMs) {
            setFaces.add(watchfaceId);
            activeFace = XiaomiWatchfaceService.toWatchfaceUUID(watchfaceId);
            return true;
        }

        @Override
        public void setWatchface(final UUID watchfaceUuid) {
            setFaceUuids.add(watchfaceUuid);
            activeFace = watchfaceUuid;
        }
    }

    private static final class FakeBackend implements XiaomiNfcRelayBridge.ApduBackend {
        boolean openOk = true;
        int openCalls = 0;
        int closeCalls = 0;
        byte[] lastCapdu = null;
        byte[] nextRapdu = GB.hexStringToByteArray("6F0C8408A0000006320101059000");

        @Override
        public boolean open() {
            openCalls++;
            return openOk;
        }

        @Override
        public byte[] transceive(final int seq, final int epoch, final byte[] capdu) {
            lastCapdu = capdu;
            return nextRapdu;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    @Test
    public void relayOpenForwardsAndRestoresWatchfaceOnClose() throws Exception {
        final FakeTransport transport = new FakeTransport(2);
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend);

        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"relay-open\",\"switch_face\":false}");
        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"relay-close\"}");

        Assert.assertTrue(transport.latch.await(2, TimeUnit.SECONDS));
        Assert.assertEquals(1, backend.openCalls);
        Assert.assertEquals(1, backend.closeCalls);
        Assert.assertEquals("relay-open-ack", transport.sent.get(0).getString("op"));
        Assert.assertTrue(transport.sent.get(0).getBoolean("ok"));
        Assert.assertEquals("relay-close-ack", transport.sent.get(1).getString("op"));
        bridge.dispose();
    }

    @Test
    public void apduRoundtripPreservesSeqEpochAndRapduHex() throws Exception {
        final FakeTransport transport = new FakeTransport(1);
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend);

        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"apdu\",\"seq\":4,\"epoch\":12,\"apdu\":\"00a4040008a00000063201010500\"}");

        Assert.assertTrue(transport.latch.await(2, TimeUnit.SECONDS));
        Assert.assertArrayEquals(GB.hexStringToByteArray("00a4040008a00000063201010500"), backend.lastCapdu);
        final JSONObject reply = transport.sent.get(0);
        Assert.assertEquals("response", reply.getString("op"));
        Assert.assertEquals(4, reply.getInt("seq"));
        Assert.assertEquals(12, reply.getInt("epoch"));
        Assert.assertEquals("6f0c8408a0000006320101059000", reply.getString("rapdu"));
        bridge.dispose();
    }

    @Test
    public void directDaemonApduRoundtripsWithoutJsonOrQuickApp() throws Exception {
        final FakeTransport transport = new FakeTransport(1);
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend);

        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "S4Q1:7:3:00a4040008a00000063201010500");

        Assert.assertTrue(transport.latch.await(2, TimeUnit.SECONDS));
        Assert.assertEquals(1, backend.openCalls);
        Assert.assertArrayEquals(GB.hexStringToByteArray("00a4040008a00000063201010500"), backend.lastCapdu);
        Assert.assertEquals(1, transport.sentDirect.size());
        Assert.assertEquals("S4R1:7:3:6f0c8408a0000006320101059000", transport.sentDirect.get(0));
        Assert.assertTrue(transport.sent.isEmpty());
        bridge.dispose();
    }

    @Test
    public void onWatchConnectedAlreadyArmedSkipsWatchfaceSwitchAndRunsSelftest() throws Exception {
        final XiaomiNfcRelayBridge[] bridgeRef = new XiaomiNfcRelayBridge[1];
        final CountDownLatch selftestDone = new CountDownLatch(1);
        final FakeTransport transport = new FakeTransport(4) {
            @Override
            public synchronized void sendDirectMessage(final String appId, final byte[] payload) {
                super.sendDirectMessage(appId, payload);
                final String msg = new String(payload, StandardCharsets.US_ASCII);
                if ("S4CTL:PING".equals(msg)) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:PONG:4:1:1:0");
                } else if ("S4CTL:DIAG".equals(msg)) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:DIAG:225:0:0:0");
                } else if ("S4CTL:SELFTEST".equals(msg)) {
                    bridgeRef[0].handleWatchMessage(appId, "S4Q1:999901:9999:00a4040008a00000063201010500");
                } else if (msg.startsWith("S4R1:999901:9999:")) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:SELFTEST_OK:14:36864:4:1");
                    selftestDone.countDown();
                }
            }
        };
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend, 0L, 0L, 0L, 500L);
        bridgeRef[0] = bridge;

        bridge.onWatchConnected();

        Assert.assertTrue(selftestDone.await(2, TimeUnit.SECONDS));
        Assert.assertTrue(bridge.isDaemonArmed());
        Assert.assertTrue(transport.setFaces.isEmpty());
        Assert.assertEquals(1, backend.openCalls);
        Assert.assertEquals("S4CTL:PING", transport.sentDirect.get(0));
        Assert.assertEquals("S4CTL:DIAG", transport.sentDirect.get(1));
        Assert.assertEquals("S4CTL:SELFTEST", transport.sentDirect.get(2));
        Assert.assertEquals("S4R1:999901:9999:6f0c8408a0000006320101059000", transport.sentDirect.get(3));
        bridge.dispose();
    }

    @Test
    public void onWatchConnectedOldV2DaemonStopsAndHotUpgradesToV3() throws Exception {
        final XiaomiNfcRelayBridge[] bridgeRef = new XiaomiNfcRelayBridge[1];
        final CountDownLatch selftestDone = new CountDownLatch(1);
        final boolean[] upgraded = {false};
        final boolean[] stopped = {false};
        final FakeTransport transport = new FakeTransport(6) {
            @Override
            public boolean setWatchfaceAndWait(final String watchfaceId, final long timeoutMs) {
                final boolean ok = super.setWatchfaceAndWait(watchfaceId, timeoutMs);
                if (XiaomiNfcRelayBridge.RELAY_WATCHFACE_ID.equals(watchfaceId) && stopped[0]) {
                    upgraded[0] = true;
                }
                return ok;
            }

            @Override
            public synchronized void sendDirectMessage(final String appId, final byte[] payload) {
                super.sendDirectMessage(appId, payload);
                final String msg = new String(payload, StandardCharsets.US_ASCII);
                if ("S4CTL:PING".equals(msg)) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:PONG:4:1:1:0");
                } else if ("S4CTL:STOP".equals(msg)) {
                    stopped[0] = true;
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:STOPPED:4:1:1:0");
                } else if ("S4CTL:DIAG".equals(msg) && upgraded[0]) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:DIAG:225:0:0:0");
                } else if ("S4CTL:SELFTEST".equals(msg)) {
                    bridgeRef[0].handleWatchMessage(appId, "S4Q1:999901:9999:00a4040008a00000063201010500");
                } else if (msg.startsWith("S4R1:999901:9999:")) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:SELFTEST_OK:14:36864:4:1");
                    selftestDone.countDown();
                }
            }
        };
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend, 0L, 0L, 0L, 120L);
        bridgeRef[0] = bridge;

        bridge.onWatchConnected();

        Assert.assertTrue(selftestDone.await(3, TimeUnit.SECONDS));
        Assert.assertTrue(stopped[0]);
        Assert.assertTrue(upgraded[0]);
        Assert.assertEquals(2, transport.setFaces.size());
        Assert.assertEquals(XiaomiNfcRelayBridge.RELAY_WATCHFACE_ID, transport.setFaces.get(0));
        Assert.assertEquals("120917345464", transport.setFaces.get(1));
        bridge.dispose();
    }

    @Test
    public void onWatchConnectedColdBootArmsViaRelayFaceThenRestoresDailyWatchface() throws Exception {
        final XiaomiNfcRelayBridge[] bridgeRef = new XiaomiNfcRelayBridge[1];
        final CountDownLatch selftestDone = new CountDownLatch(1);
        final boolean[] armed = {false};
        final FakeTransport transport = new FakeTransport(4) {
            @Override
            public boolean setWatchfaceAndWait(final String watchfaceId, final long timeoutMs) {
                final boolean ok = super.setWatchfaceAndWait(watchfaceId, timeoutMs);
                if (XiaomiNfcRelayBridge.RELAY_WATCHFACE_ID.equals(watchfaceId)) {
                    armed[0] = true;
                }
                return ok;
            }

            @Override
            public synchronized void sendDirectMessage(final String appId, final byte[] payload) {
                super.sendDirectMessage(appId, payload);
                final String msg = new String(payload, StandardCharsets.US_ASCII);
                if ("S4CTL:PING".equals(msg) && armed[0]) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:PONG:4:1:1:0");
                } else if ("S4CTL:SELFTEST".equals(msg)) {
                    bridgeRef[0].handleWatchMessage(appId, "S4Q1:999901:9999:00a4040008a00000063201010500");
                } else if (msg.startsWith("S4R1:999901:9999:")) {
                    bridgeRef[0].handleWatchMessage(appId, "S4CTL:SELFTEST_OK:14:36864:4:1");
                    selftestDone.countDown();
                }
            }
        };
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend, 0L, 0L, 0L, 120L);
        bridgeRef[0] = bridge;

        bridge.onWatchConnected();

        Assert.assertTrue(selftestDone.await(3, TimeUnit.SECONDS));
        Assert.assertTrue(bridge.isDaemonArmed());
        Assert.assertEquals(2, transport.setFaces.size());
        Assert.assertEquals(XiaomiNfcRelayBridge.RELAY_WATCHFACE_ID, transport.setFaces.get(0));
        Assert.assertEquals("120917345464", transport.setFaces.get(1));
        Assert.assertEquals("120917345464", XiaomiWatchfaceService.toWatchfaceId(transport.activeFace));
        bridge.dispose();
    }

    @Test
    public void invalidApduIsIgnoredWithoutCallingBackend() throws Exception {
        final FakeTransport transport = new FakeTransport(1);
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend);

        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"apdu\",\"seq\":0,\"epoch\":1,\"apdu\":\"zzzz\"}");
        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"relay-open\",\"switch_face\":false}");

        Assert.assertTrue(transport.latch.await(2, TimeUnit.SECONDS));
        Assert.assertNull(backend.lastCapdu);
        Assert.assertEquals(1, transport.sent.size());
        Assert.assertEquals("relay-open-ack", transport.sent.get(0).getString("op"));
        bridge.dispose();
    }

    @Test
    public void staleQueuedRelayOpensDuringFaceSwitchAreDropped() throws Exception {
        final CountDownLatch switchEntered = new CountDownLatch(1);
        final CountDownLatch releaseSwitch = new CountDownLatch(1);
        final FakeTransport transport = new FakeTransport(3) {
            @Override
            public boolean setWatchfaceAndWait(final String watchfaceId, final long timeoutMs) {
                switchEntered.countDown();
                try {
                    Assert.assertTrue(releaseSwitch.await(2, TimeUnit.SECONDS));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return super.setWatchfaceAndWait(watchfaceId, timeoutMs);
            }
        };
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend, 0L);

        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"relay-open\",\"switch_face\":true}");
        Assert.assertTrue(switchEntered.await(2, TimeUnit.SECONDS));

        for (int i = 0; i < 4; i++) {
            bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                    "{\"relay\":\"s4\",\"op\":\"relay-open\",\"switch_face\":true}");
        }
        releaseSwitch.countDown();

        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (transport.relaunches.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Assert.assertEquals(1, transport.relaunches.size());
        Assert.assertEquals(1, transport.sent.size());
        Assert.assertEquals("yield-for-face", transport.sent.get(0).getString("op"));
        Assert.assertEquals(1, backend.openCalls);

        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"relay-open\",\"switch_face\":true,\"watch_status\":\"active\"}");

        Assert.assertTrue(transport.latch.await(2, TimeUnit.SECONDS));
        Assert.assertEquals(2, backend.openCalls);
        Assert.assertEquals(2, transport.sent.size());
        Assert.assertEquals("relay-open-ack", transport.sent.get(1).getString("op"));
        Assert.assertTrue(transport.sent.get(1).getBoolean("ok"));
        bridge.dispose();
    }

    @Test
    public void alreadyOnRelayFaceWithStoppedStatusBouncesToFallbackAndRetriggers() throws Exception {
        final FakeTransport transport = new FakeTransport(2);
        transport.activeFace = XiaomiWatchfaceService.toWatchfaceUUID(XiaomiNfcRelayBridge.RELAY_WATCHFACE_ID);
        final FakeBackend backend = new FakeBackend();
        final XiaomiNfcRelayBridge bridge = new XiaomiNfcRelayBridge(transport, backend, 0L);

        bridge.handleWatchMessage(XiaomiNfcRelayBridge.RELAY_APP_ID,
                "{\"relay\":\"s4\",\"op\":\"relay-open\",\"switch_face\":true,\"watch_status\":\"stopped\"}");

        Assert.assertTrue(transport.latch.await(2, TimeUnit.SECONDS));
        Assert.assertEquals("yield-for-face", transport.sent.get(0).getString("op"));
        Assert.assertEquals(2, transport.setFaces.size());
        Assert.assertEquals("120917345464", transport.setFaces.get(0));
        Assert.assertEquals(XiaomiNfcRelayBridge.RELAY_WATCHFACE_ID, transport.setFaces.get(1));
        Assert.assertEquals(1, transport.relaunches.size());
        bridge.dispose();
    }
}
