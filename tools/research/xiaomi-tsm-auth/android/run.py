#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run isolated Binder-auth research in the installed debug app; never install APK."""
import argparse
import os
from pathlib import Path
import selectors
import subprocess
import tempfile
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--sdk', required=True, type=Path)
    p.add_argument('--ndk', required=True, type=Path)
    p.add_argument('--java-home', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path, help='sanitized log')
    a = p.parse_args()
    package = 'nodomain.freeyourgadget.gadgetbridge'
    adb = [str(a.sdk/'platform-tools/adb'), '-s', a.serial]
    source = Path(__file__).resolve().parent
    dex_name = 'tsm-auth-research.dex'
    library_name = f'libtsmauthresearch-{os.getpid()}.so'
    app_dir = '/data/user/0/'+package+'/code_cache/'
    pid = subprocess.check_output(adb+['shell', 'pidof', package], text=True).strip()
    if not pid.isdigit():
        raise SystemExit('expected one running debug app process')
    subprocess.run(adb+['shell', 'run-as', package, 'id'], check=True)
    exists = subprocess.run(adb+['shell', 'run-as', package, 'test', '-e', 'code_cache/'+dex_name])
    if exists.returncode == 0:
        raise SystemExit('existing research DEX found; resolve its ownership before replacing')
    hosts = list((a.ndk/'toolchains/llvm/prebuilt').glob('*'))
    if len(hosts) != 1:
        p.error('expected one NDK host toolchain')
    platform = a.sdk/'platforms/android-36/android.jar'
    d8 = a.sdk/'build-tools/36.0.0/d8'
    evidence = []
    copied = []
    with tempfile.TemporaryDirectory(prefix='tsm-auth-', dir='/tmp/.agents') as temp:
        work = Path(temp)
        subprocess.run([str(a.java_home/'bin/javac'), '--release', '11', '-cp',
                        str(platform), '-d', str(work), str(source/'TsmAuthProbe.java')], check=True)
        subprocess.run([str(d8), '--lib', str(platform), '--output', str(work),
                        *map(str, (work/'research').rglob('*.class'))],
                        env=dict(os.environ, JAVA_HOME=str(a.java_home)), check=True)
        library = work/library_name
        subprocess.run([str(hosts[0]/'bin/aarch64-linux-android26-clang++'),
                        '-shared', '-fPIC', '-std=c++17', '-static-libstdc++',
                        '-Wall', '-Wextra', '-Werror', str(source/'attach.cpp'),
                        '-llog', '-o', str(library)], check=True)
        capture = None
        selector = selectors.DefaultSelector()
        try:
            for local, name in ((work/'classes.dex', dex_name), (library, library_name)):
                copied.append(name)
                subprocess.run(adb+['push', str(local), '/data/local/tmp/'+name], check=True)
                subprocess.run(adb+['shell', 'run-as', package, 'cp', '/data/local/tmp/'+name,
                                    'code_cache/'+name], check=True)
                subprocess.run(adb+['shell', 'run-as', package, 'chmod', '444', 'code_cache/'+name], check=True)
            capture = subprocess.Popen(adb+['logcat', '-T', '1', '--pid='+pid, '-v', 'time',
                                            'GBTsmAuthResearch:I', '*:S'], stdout=subprocess.PIPE)
            selector.register(capture.stdout, selectors.EVENT_READ)
            subprocess.run(adb+['shell', 'am', 'attach-agent', package, app_dir+library_name], check=True)
            deadline = time.monotonic()+25
            pending = b''
            done = False
            while not done and time.monotonic() < deadline:
                if not selector.select(max(0, deadline-time.monotonic())):
                    break
                data = os.read(capture.stdout.fileno(), 65536)
                if not data:
                    break
                pending += data
                while b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    text = line.decode('utf-8', 'replace')
                    if 'GBTsmAuthResearch' not in text:
                        continue
                    evidence.append(text)
                    print(text, flush=True)
                    done |= 'serviceUnbound=true' in text or 'loader failed' in text
            if not any('result=' in row for row in evidence):
                raise RuntimeError('no completed Binder result; do not infer authentication')
        finally:
            selector.close()
            if capture:
                capture.terminate()
                capture.wait(timeout=5)
            for name in copied:
                subprocess.run(adb+['shell', 'run-as', package, 'rm', '-f', 'code_cache/'+name], check=True)
                subprocess.run(adb+['shell', 'rm', '-f', '/data/local/tmp/'+name], check=True)
            a.output.parent.mkdir(parents=True, exist_ok=True)
            a.output.write_text('\n'.join(evidence)+'\n')


if __name__ == '__main__':
    main()
