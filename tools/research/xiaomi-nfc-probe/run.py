#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run the allowlisted SE probe against the existing debug APK; never install one."""
import argparse
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import tempfile
import time


def fields(data):
    """Decode only protobuf varint/bytes fields, rejecting truncated input."""
    pos = 0

    def varint():
        nonlocal pos
        result = 0
        for shift in range(0, 70, 7):
            if pos == len(data):
                raise ValueError("truncated protobuf varint")
            value = data[pos]
            pos += 1
            result |= (value & 127) << shift
            if not value & 128:
                return result
        raise ValueError("oversized protobuf varint")

    result = {}
    while pos < len(data):
        key = varint()
        number, wire = key >> 3, key & 7
        if not number:
            raise ValueError("invalid protobuf field zero")
        if wire == 0:
            value = varint()
        elif wire == 2:
            length = varint()
            if length > len(data) - pos:
                raise ValueError("truncated protobuf bytes")
            value = data[pos:pos + length]
            pos += length
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")
        if number in result:
            raise ValueError("duplicate field in NFC response")
        result[number] = value
    return result


def decode_response(raw, capability=False):
    outer = fields(bytes.fromhex(raw))
    if outer.get(1) != 5 or outer.get(2) != (10 if capability else 0):
        raise ValueError("unexpected response command")
    if outer.get(100, 0):
        raise ValueError(f"watch protocol error {outer[100]}")
    nfc = fields(outer[7])
    if capability:
        return {"capability": nfc[10]}
    payload = nfc[1]
    if len(payload) < 6:
        raise ValueError("truncated NFC envelope")
    status = int.from_bytes(payload[:4], "big")
    length = int.from_bytes(payload[4:6], "big")
    if length != len(payload) - 6:
        raise ValueError("NFC response length mismatch")
    return {"status": status, "data": payload[6:].hex().upper()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="verified wireless ADB serial")
    parser.add_argument("--address", required=True, help="explicit target watch Bluetooth address")
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--metadata", action="store_true", help="also read CPLC; report only chip type")
    parser.add_argument("--output", type=Path, help="sanitized JSON evidence path")
    args = parser.parse_args()
    args.address = args.address.upper()
    if not re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", args.address):
        parser.error("--address must be a Bluetooth MAC address")
    adb = ["adb", "-s", args.serial]
    package = "nodomain.freeyourgadget.gadgetbridge"

    def call(*parts, **kwargs):
        return subprocess.check_output(adb + list(parts), timeout=20, **kwargs)

    # Both run-as and attach-agent enforce Android's application debug boundary.
    call("shell", "run-as", package, "id")
    pid = call("shell", "pidof", package, text=True).strip()
    if not pid.isdigit():
        raise RuntimeError("expected exactly one running Gadgetbridge main process")
    base = Path("/tmp/.agents")
    base.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="gb-nfc-probe-", dir=base) as local:
        name = f"libgbnfcprobe-{os.getpid()}.so"
        library = Path(local) / name
        toolchains = list((args.ndk / "toolchains/llvm/prebuilt").glob("*"))
        if len(toolchains) != 1:
            raise RuntimeError("select an NDK with one host toolchain")
        compiler = toolchains[0] / "bin/aarch64-linux-android28-clang++"
        platform = "darwin" if os.uname().sysname == "Darwin" else "linux"
        subprocess.run([
            str(compiler), "-shared", "-fPIC", "-std=c++17", "-O2",
            "-I", str(args.java_home / "include"),
            "-I", str(args.java_home / "include" / platform),
            str(Path(__file__).with_name("agent.cpp")), "-llog", "-static-libstdc++",
            "-o", str(library),
        ], check=True, timeout=60)
        remote = "/data/local/tmp/" + name
        app_path = "/data/user/0/" + package + "/code_cache/" + name
        evidence = {"transport": "existing authenticated Gadgetbridge connection", "results": {}}
        copied = False

        def probe(mode):
            capture = subprocess.Popen(adb + ["logcat", "-T", "1", "--pid=" + pid, "-v", "time"],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            selector = selectors.DefaultSelector()
            selector.register(capture.stdout, selectors.EVENT_READ)
            try:
                call("shell", "am", "attach-agent", package, app_path + "=" + mode + "@" + args.address)
                deadline = time.monotonic() + 10
                buffer = b""
                while time.monotonic() < deadline:
                    while b"\n" not in buffer:
                        if not selector.select(max(0, deadline - time.monotonic())):
                            raise TimeoutError(f"no NFC response for {mode}; no success inferred")
                        chunk = os.read(capture.stdout.fileno(), 65536)
                        if not chunk:
                            raise RuntimeError("ADB log stream ended")
                        buffer += chunk
                    raw_line, buffer = buffer.split(b"\n", 1)
                    line = raw_line.decode("utf-8", "replace")
                    if "GBNfcProbe" in line and any(x in line for x in ("Missing ", "Java exception", "nothing sent", "Ambiguous")):
                        raise RuntimeError("native diagnostic refused the operation: " + line.strip())
                    firmware = re.search(r"Cached watch firmware=(\S+)", line)
                    if firmware:
                        evidence["watch_firmware"] = firmware[1]
                    match = re.search(r"Got command: ([0-9A-F]+)", line)
                    if not match:
                        continue
                    outer = fields(bytes.fromhex(match[1]))
                    if outer.get(1) != 5 or outer.get(2) != (10 if mode == "capability" else 0):
                        continue
                    result = decode_response(match[1], mode == "capability")
                    if mode == "cplc":
                        body = result.pop("data")
                        result["sw"] = body[-4:]
                        result["cplc_tlv_present"] = body.startswith("9F7F2A")
                        if result["cplc_tlv_present"]:
                            result["fabricator_and_ic_type"] = body[6:14]
                    evidence["results"][mode] = result
                    print(mode, json.dumps(result), flush=True)
                    return result
                raise TimeoutError(f"no NFC response for {mode}; no success inferred")
            finally:
                selector.close()
                capture.terminate()
                capture.wait(timeout=5)
                capture.stdout.close()

        try:
            call("push", str(library), remote)
            copied = True
            call("shell", "run-as", package, "cp", remote, "code_cache/" + name)
            probe("capability")
            try:
                opened = probe("open")
                if opened["status"] not in (1, 224):
                    raise RuntimeError("SE did not open; test APDU skipped")
                selected = probe("select-test")
                if selected != {"status": 224, "data": "6A82"}:
                    raise RuntimeError("test AID response differs from recorded S4 result")
                if args.metadata:
                    probe("cplc")
            finally:
                closed = probe("close")
                if closed["status"] not in (1, 224):
                    raise RuntimeError("SE channel close was not confirmed")
        finally:
            if copied:
                call("shell", "run-as", package, "rm", "-f", "code_cache/" + name)
            call("shell", "rm", "-f", remote)
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    main()
