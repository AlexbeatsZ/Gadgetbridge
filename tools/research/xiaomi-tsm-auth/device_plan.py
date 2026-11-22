#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build five exact read-only query plans from actual K70/S4 metadata.

No account database, token, card key, balance or complete card number is read.
The private plans contain SE identifiers and must be deleted after the session.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from read_only import validate

PACKAGE = 'nodomain.freeyourgadget.gadgetbridge'
LIVE = Path('/tmp/.agents/gadgetbridge-transit-research/tsm-auth-live')


def official_alias(reference_java):
    # This is the SDK's anonymous catalog branch (loginPolicy=3), not a
    # user account credential. Do not log the static catalog application key
    # or retain the catalog's unrelated Bluetooth configuration fields.
    source = reference_java.read_text()
    key = re.search(r'Cookie: auth_key=([A-Za-z0-9]+)', source)
    if key is None:
        raise ValueError('inspected official anonymous catalog declaration required')
    url = 'https://hlth.io.mi.com/app/v1/product/get_config_info_by_category?'
    url += urllib.parse.urlencode({'locale': 'zh_cn', 'data': '{"category":"wearable"}'})
    request = urllib.request.Request(url, headers={'Cookie': 'auth_key=' + key[1]})
    with urllib.request.urlopen(request, timeout=20) as response:
        code, data = response.status, json.load(response)
    rows = [v for v in data.get('result', {}).get('list', [])
            if v.get('model') == 'mijia.watch.o62']
    if code != 200 or data.get('code') != 0 or len(rows) != 1:
        raise ValueError('unambiguous official S4 catalog record required')
    return {k: rows[0][k] for k in ('model', 'alias', 'pd_id', 'pd_name')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--watch-metadata', type=Path, required=True)
    parser.add_argument('--catalog-reference-java', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if args.output_dir.resolve() != LIVE.resolve():
        parser.error('private plans must stay in the task temporary directory')
    adb = [str(args.adb), '-s', args.serial]

    def call(*parts, **kwargs):
        return subprocess.check_output(adb + list(parts), text=True, timeout=20, **kwargs).strip()

    def root(script):
        return call('shell', 'su', input=script + '\n')

    def version(package):
        text = call('shell', 'dumpsys', 'package', package)
        return (re.search(r'\bversionCode=(\d+)', text)[1],
                re.search(r'\bversionName=([^\r\n]+)', text)[1].strip())

    catalog = official_alias(args.catalog_reference_java)
    watch = json.loads(args.watch_metadata.read_text())
    if watch.get('deviceModel') != catalog['alias']:
        raise ValueError('watch TSM model must match the independently verified catalog alias')
    source_xml = root('cat /data/user/0/com.miui.tsmclient/shared_prefs/pref_com_tsmclient_smartcard.xml')
    source_cplc = next(n.text for n in ET.fromstring(source_xml) if n.attrib.get('name') == 'key_cplc')
    for cplc in (source_cplc, watch['cplc']):
        if not re.fullmatch(r'[0-9A-Fa-f]{84}', cplc):
            raise ValueError('expected 42-byte CPLC')
    if source_cplc.upper() == watch['cplc'].upper():
        raise ValueError('source and target must be distinct SEs')
    source_product = root('sqlite3 -readonly /data/user/0/com.miui.tsmclient/databases/tsmclient.db '
        "'SELECT json_extract(value,\"$.cardName\") FROM cache WHERE key=\"SPTC_MOT\";'")
    if source_product != 'SPTC_MOT':
        raise ValueError('expected existing source product metadata')
    app_code, app_name = version(PACKAGE)
    tsm_code, tsm_name = version('com.miui.tsmclient')
    common = {
        'appPackageName': PACKAGE, 'appVersionCode': app_code, 'appVersionName': app_name,
        'tsmclientVersionCode': tsm_code, 'tsmclientVersionName': tsm_name,
        'miuiRomType': 'ANDROID', 'miuiSystemVersion': call('shell', 'getprop', 'ro.build.version.incremental'),
        'hyperOSVersion': '', 'romType': '', 'androidVersion': call('shell', 'getprop', 'ro.build.version.release'),
        'roProductName': call('shell', 'getprop', 'ro.product.name'),
        'roProductDevice': call('shell', 'getprop', 'ro.product.device'), 'lang': 'zh_CN',
        'cardName': source_product}
    source = dict(common, cplc=source_cplc, deviceModel=common['roProductDevice'])
    target = dict(common, **watch)
    requests = {
        'source-out': ('/api/login/acl/checkServiceAvailable', dict(source, cardActionType='TRANSFER_OUT', os='ANDROID')),
        'source-issued': ('/api/login/spcard/queryIssuedCardList', dict(source, type='1')),
        'target-in': ('/api/login/acl/checkServiceAvailable', dict(target, cardActionType='TRANSFER_IN', os='ANDROID')),
        'target-product': ('/api/login/spcard/v2/queryCardProduct', dict(target, type='1', supportCardTransfer='true')),
        'target-cloud': ('/api/login/spcard/v2/queryShiftInCardProducts', dict(target, type='1',
            supportCardTransfer='true', tsmclientVersionCode=app_code, tsmclientVersionName=app_name))}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, (path, params) in requests.items():
        command = {'path': path, 'params': params}
        validate(command)
        filename = args.output_dir / ('current-' + name + '-plan.json')
        fd = os.open(filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(command, stream)
    print(json.dumps({'plans_prepared': len(requests), 'catalog': catalog,
                      'source_product': source_product, 'identifiers_logged': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
