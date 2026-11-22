# Xiaomi S4 NFC research probes

These are diagnostics, not a transit-card implementation. No production UI, version bump or APK is included. The SE probe uses an already running debug build and its authenticated connection. It permits only capability query, open, SELECT of fictitious AID `F0010203040506`, optional CPLC metadata, and close. It cannot accept arbitrary APDUs, export a card, provision a card, or change NFC routing.

Verified on K70 Android 16, Gadgetbridge 2026.11.16/code272, Xiaomi Watch S4 firmware 3.201.79. The live transport is Classic Bluetooth SPP V2. BLE behavior has not been verified.

## SE management channel

Use existing JDK 21, Android NDK and wireless ADB. Re-discover the serial first. The Bluetooth address is mandatory, and ambiguous connected targets are rejected.

```sh
python3 tools/research/xiaomi-nfc-probe/run.py \
  --serial '<verified-wireless-serial>' \
  --address '<target-watch-bluetooth-address>' \
  --ndk /Users/meta/Library/Android/sdk/ndk/28.2.13676358 \
  --java-home /Users/meta/.local/share/mise/installs/java/zulu-21 \
  --metadata \
  --output /tmp/.agents/s4-nfc-se-result.json
```

The agent queues one fixed operation per attach. The runner captures the actual incoming response, confirms closure in `finally`, removes its temporary library, and only writes sanitized CPLC chip type. Missing responses are failures. No firmware, app key, account token, full CPLC or card identifier is written to evidence. Already loaded native library mappings remain until Android ends the process; there are no hooks or background callbacks.

```sh
python3 -m unittest discover -s tools/research/xiaomi-nfc-probe -p test_response.py -v
```

The fixtures are actual captured responses, plus malformed input checks. Native JNI is coupled to this debug APK's classes. It checks lookup exceptions before dependent calls. A separate, discarded cleanup experiment initially used the wrong Java return signature (`List` instead of `Collection`) and crashed the app; the normal app connection was recovered and data checked. Do not add unverified reflection calls to a live agent.

## Read-only Lua/native diagnostic

`watch/build.py` uses the existing NDK Clang to build an import-free ARM Thumb ET_REL module, inspect its symbols/relocations, and embed its bytes into `main.lua` generated from `read-only.lua.in`.

```sh
python3 tools/research/xiaomi-nfc-probe/watch/build.py \
  --ndk /Users/meta/Library/Android/sdk/ndk/28.2.13676358 \
  --output /tmp/.agents/s4-native-readonly
```

It calls no firmware addresses and accesses no NFC or SE device. Lua writes only the diagnostic companion's private files, then runs `help`, `ls`, `ps`, an empty module's `insmod`, `lsmod` and `rmmod`. A successful shell return alone is insufficient: the module must appear after loading and disappear after unloading.

The companion sources are under `watch/companion`. Build them in a task temporary directory with the existing S4-Vela-Probe toolkit 2.0.5 and its existing `common/logo.png`; dependencies and icon are not duplicated here. Its runtime file feature must first create `ready.txt`. It polls diagnostic files through `internal://files/...`, sends them over interconnect, and exits after 6.5 seconds so the Lua watchface can execute. It is not an end-user screen or an NFC feature. The archived companion contains small telemetry adjustments; it was rebuilt after cleanup, while the device experiment used versionCode 5.

For a watchface package, use the existing community [LuaDevTemplate](https://github.com/FangAiden/LuaDevTemplate) compiler workflow, putting the generated Lua in `watchface/fprj/app/lua/main.lua`. Record the watch's original face list/current ID first. In this experiment DeviceType 362 compiled and installed on S4, despite the compiler printing “MiWatchS3”. The compiler produced a valid package then threw on exit; successful device installation and Lua return telemetry, rather than the compiler exit status, established execution. Its output header needed the template's `set_face_id.ps1` treatment (byte 5=10 and the 10-byte ID at offset 40). Do not overwrite an existing face ID.

On this actual S4, private files are `/data/quickapp/files/com.meta.s4nativeprobe/`; `/data/files/...` did not exist. `file.copy` of the ELF resource did not deliver a module, so Lua writes the embedded empty module directly. This shell rejected `2>&1`; the archived Lua uses only explicit stdout file paths. Do not treat those earlier failures as module-loader failures.

After collecting telemetry, restore the original watchface, remove only the diagnostic watchface and companion, and remove this diagnostic directory. The actual cleanup used a temporary Lua face containing only:

```lua
pcall(function() os.execute('rm -rf /data/quickapp/files/com.meta.s4nativeprobe') end)
```

Then the original face was restored and the cleanup face removed. Confirm both app and face lists afterward. This experiment left all original nine faces and the original current face in place, and the probe RPK was absent. No firmware was flashed.

## What remains

A successful wired SE SELECT does not demonstrate RF host card emulation. Native module loading also does not demonstrate an RF APDU callback. The next RF experiment requires a verified S4 firmware ABI for registering only the fictitious AID on the host, receiving a reader's SELECT, returning a fixed test response, and unregistering it while preserving SE/default routes. Only after that may SPP latency/WTX and a phone test-card backend be assessed. Real transit-card use needs issuer authorization and SE access controls.

Full migration research, exact endpoints, firmware evidence, limitations and next experiments are in `docs/design/xiaomi-s4-transit-card.md`.
