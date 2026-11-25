#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Inspect public issuer assets only; never calls card/account business APIs.

The lookup normalizer describes the observed H5 query format. It does not
establish the university's backend key or prove migration preserves identity.
"""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlparse
from urllib.request import Request, urlopen


ASSETS = {
    'public-campus-card-list.json': 'https://wind.fmswift.net/wind/front/data/partnerCardData/cobrand_card/sptcc/campuscard/cardList.json',
    'index.7c61fc0b.js': 'https://frontend.fmswift.net/wind/front/w-202308-cobrand-card/js/index.7c61fc0b.js',
    'chunk-49ae6640.e3c89e80.js': 'https://frontend.fmswift.net/wind/front/w-202308-cobrand-card/js/chunk-49ae6640.e3c89e80.js',
    'chunk-36847e1b.791d9362.js': 'https://frontend.fmswift.net/wind/front/w-202308-cobrand-card/js/chunk-36847e1b.791d9362.js',
}
ANCHORS = {
    'index.7c61fc0b.js': (
        '/wind/{cardtype}/1/0/get-token', '/wind/{cardtype}/1/0/check-card',
        '/wind/{cardtype}/1/0/issue-card-verify', 'appKeyIndex:t.appKeyIndex',
        'timestamp:t.timestamp,sign:t.sign',
    ),
    'chunk-49ae6640.e3c89e80.js': (
        'cardNo:t.faceNo', '19===t.cardNo.length?t.cardNo:"31047700"+t.cardNo',
        'cardNoList:e', 'e.cardInfoList||[]', 'coBrandCardName',
    ),
    'chunk-36847e1b.791d9362.js': (
        'openCampusCardCertified', 'encryCertificateForCampus', 'partnerToken',
        'partnerNo:this.cardObj.partnerNo', 'coBrandCard:this.cardObj.coBrandCard',
        'cplc:r,userId:a',
    ),
}


def normalize_lookup_number(number):
    """Strict supported subset of the H5's 19-digit/11-digit lookup rule.

    Caller owns private comparison; this function never prints or saves input.
    It is not a parser for PAN/UID/CPLC or a general card-number conversion.
    """
    if not isinstance(number, str) or not re.fullmatch(r'[0-9]{11}|[0-9]{19}', number):
        raise ValueError('expected an 11- or 19-digit ASCII lookup number')
    return number if len(number) == 19 else '31047700' + number


def fudan_metadata(catalog):
    found = []

    def visit(value):
        if isinstance(value, dict):
            if value.get('coBrandCardName') == '复旦大学':
                found.append(value)
            for item in value.values():
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)
    visit(catalog)
    if len(found) != 1:
        raise ValueError('expected exactly one Fudan University product')
    row = found[0]
    # Explicit public metadata allowlist: unrelated entries/credentials excluded.
    result = {k: row.get(k) for k in ('coBrandCardName', 'partnerNo', 'coBrandCard',
                                     'campusTag', 'cobrandTag', 'cardTag', 'openCardFree')}
    info = row.get('openCardInfo', {})
    result['openCardInfo'] = {k: info.get(k) for k in ('xyTitle', 'xySummary', 'xyContent')}
    return result


def inspect_assets(root):
    root = Path(root)
    anchors = []
    for filename, patterns in ANCHORS.items():
        content = (root / filename).read_text(encoding='utf-8')
        for pattern in patterns:
            offsets = [m.start() for m in re.finditer(re.escape(pattern), content)]
            anchors.append({'file': filename, 'anchor': pattern,
                            'character_offsets': offsets, 'found': bool(offsets)})
    return {
        'scope': 'public static assets; no personalized request or credential',
        'fudan_product': fudan_metadata(json.loads((root / 'public-campus-card-list.json').read_text())),
        'anchors': anchors,
        'all_expected_anchors_found': all(x['found'] for x in anchors),
        'not_established': ['backend sole key', 'card number continuity',
                            'phone-to-S4 campus preservation'],
    }


def fetch_public_assets(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    manifest = []
    for filename, url in ASSETS.items():
        # Four fixed HTTPS GETs. No cookies, account tokens or business POSTs.
        request = Request(url, headers={'User-Agent': 'Gadgetbridge-public-asset-research/1.0'})
        with urlopen(request, timeout=15) as response:
            if urlparse(response.geturl()).hostname not in {'wind.fmswift.net', 'frontend.fmswift.net'}:
                raise ValueError('unexpected public-asset redirect')
            body = response.read(2_000_001)
            if len(body) > 2_000_000:
                raise ValueError('public asset exceeded research size limit')
            manifest.append({'url': url, 'file': filename, 'http_status': response.status,
                             'bytes': len(body)})
        (root / filename).write_bytes(body)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets-root', type=Path, required=True)
    parser.add_argument('--fetch-public', action='store_true', help='GET only the four fixed public assets')
    args = parser.parse_args()
    manifest = fetch_public_assets(args.assets_root) if args.fetch_public else None
    result = inspect_assets(args.assets_root)
    if manifest is not None:
        result['http'] = manifest
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result['all_expected_anchors_found']:
        raise SystemExit('asset version changed or expected code was not found; review manually')


if __name__ == '__main__':
    main()
