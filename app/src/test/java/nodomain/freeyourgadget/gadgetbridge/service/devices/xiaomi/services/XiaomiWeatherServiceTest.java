package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.WeatherLocation;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.WeatherLocations;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class XiaomiWeatherServiceTest extends TestBase {
    private XiaomiSupport support;
    private XiaomiWeatherService service;
    private WeatherSpec spec;

    @Before
    public void setupService() {
        final GBDevice device = new GBDevice("C4:57:81:00:00:02", "Xiaomi Watch S4 0002", null, null, DeviceType.XIAOMI_WATCH_S4);
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putBoolean("feat_multiple_weather_locations", true).commit();
        support = mock(XiaomiSupport.class);
        when(support.getDevice()).thenReturn(device);
        service = new XiaomiWeatherService(support);
        Weather.setWeatherSpec(Collections.emptyList());
        spec = new WeatherSpec();
        spec.setLocation("Example City");
        spec.setTimestamp(1794800000);
        spec.setCurrentTemp(289);
        spec.setTodayMinTemp(283);
        spec.setTodayMaxTemp(293);
        spec.setCurrentConditionCode(800);
    }

    @After
    public void clearWeather() {
        Weather.setWeatherSpec(Collections.emptyList());
    }

    @Test
    public void initialLocationReplySendsCachedWeatherOnce() {
        Weather.setWeatherSpec(List.of(spec));
        service.handleCommand(locationsReply(WeatherLocations.newBuilder().build()));
        assertEquals(1, sentCommands().stream().filter(cmd -> cmd.getSubtype() == 0).count());
        clearInvocations(support);
        service.handleCommand(locationsReply(WeatherLocations.newBuilder().build()));
        verify(support, never()).sendCommand(eq("set current weather"), any(XiaomiProto.Command.class));
    }

    @Test
    public void emptyPhoneCachePreservesWatchCities() {
        service.handleCommand(locationsReply(WeatherLocations.newBuilder().addLocation(
                WeatherLocation.newBuilder().setCode("accu:1").setName("Existing city")).build()));
        service.onSendWeather();
        verify(support, never()).sendCommand(anyString(), any(XiaomiProto.Command.class));
    }

    @Test
    public void malformedListDoesNotInitializeOrRemoveCities() {
        Weather.setWeatherSpec(List.of(spec));
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).build());
        service.onSendWeather();
        verify(support, never()).sendCommand(anyString(), any(XiaomiProto.Command.class));
        verify(support, never()).setFeatureSupported(anyString(), anyBoolean());
        verify(support).sendCommand("get weather locations", 10, 5);
    }

    @Test
    public void weatherRequestCanIdentifyCityByKeyOnly() {
        Weather.setWeatherSpec(List.of(spec));
        service.sendCurrentConditions(spec);
        final String key = sentCommands().get(0).getWeather().getCurrent().getMetadata().getLocationKey();
        assertEquals("Example City", sentCommands().get(0).getWeather().getCurrent().getMetadata().getCityName());
        clearInvocations(support);
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(10).setSubtype(3).setWeather(
                nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Weather.newBuilder().setLocation(
                        WeatherLocation.newBuilder().setCode(key))).build());
        assertEquals(3, sentCommands().size());
    }

    @Test
    public void unknownRequestedCityDoesNotReceivePrimaryCityWeather() {
        Weather.setWeatherSpec(List.of(spec));
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(10).setSubtype(3).setWeather(
                nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Weather.newBuilder().setLocation(
                        WeatherLocation.newBuilder().setCode("accu:unknown"))).build());
        verify(support, never()).sendCommand(anyString(), any(XiaomiProto.Command.class));
    }

    private XiaomiProto.Command locationsReply(final WeatherLocations locations) {
        return XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).setWeather(
                nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Weather.newBuilder().setLocations(locations)).build();
    }

    private List<XiaomiProto.Command> sentCommands() {
        final ArgumentCaptor<XiaomiProto.Command> captor = ArgumentCaptor.forClass(XiaomiProto.Command.class);
        verify(support, atLeastOnce()).sendCommand(anyString(), captor.capture());
        return captor.getAllValues();
    }
}
