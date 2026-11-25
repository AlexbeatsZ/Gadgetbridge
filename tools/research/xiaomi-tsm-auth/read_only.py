# SPDX-License-Identifier: AGPL-3.0-or-later
"""Exact read-only TSM routes, using the fresh approved in-memory session."""
import json
from pathlib import Path
import stat
import time
from datetime import datetime, timezone
import urllib.error
import urllib.request

from tsm_protocol import READ_ONLY_PATHS, decrypt, response_shape

ACL = '/api/login/acl/checkServiceAvailable'
PRODUCTS = '/api/login/spcard/v2/queryShiftInCardProducts'
ISSUED = '/api/login/spcard/queryIssuedCardList'
ALLOWED_PARAMS = frozenset({
    'cardName', 'cardActionType', 'deviceModel', 'miuiRomType',
    'miuiSystemVersion', 'os', 'hyperOSVersion', 'romType', 'androidVersion',
    'roProductName', 'roProductDevice', 'lang', 'tsmclientVersionCode',
    'tsmclientVersionName', 'appVersionName', 'appVersionCode',
    'appPackageName', 'firmwareVersion', 'cplc', 'type', 'supportCardTransfer',
})


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A redirect must never forward this request's sensitive cookie/body.
        return None


def validate(command):
    if not isinstance(command, dict) or set(command) != {'path', 'params'}:
        raise ValueError('expected path and params')
    path, params = command['path'], command['params']
    if path not in READ_ONLY_PATHS or not isinstance(params, dict):
        raise ValueError('unapproved query')
    if set(params) - ALLOWED_PARAMS:
        raise ValueError('unknown request field')
    if any(not isinstance(v, str) or len(v) > 512 for v in params.values()):
        raise ValueError('invalid field type or length')
    if path == ACL and params.get('cardActionType') not in ('TRANSFER_OUT', 'TRANSFER_IN'):
        raise ValueError('only transfer eligibility can be checked')
    if 'appPackageName' in params and params['appPackageName'] != 'nodomain.freeyourgadget.gadgetbridge':
        raise ValueError('do not impersonate another application')
    if not all(params.get(k) for k in ('cardName', 'deviceModel', 'cplc')):
        raise ValueError('verified product and device metadata required')
    return path, params


def public_result(value):
    """Expose status/eligibility values only, never identifiers or credentials."""
    if isinstance(value, list):
        return [public_result(v) for v in value[:100]]
    if not isinstance(value, dict):
        return None
    result = {}
    for k, v in value.items():
        if k in ('code', 'errCode', 'errorCode', 'resultCode', 'success', 'isSuccess',
                 'supportTransferIn', 'supportTransferOut', 'canTransferIn',
                 'canTransferOut', 'isSupportTransferIn', 'isSupportTransferOut'):
            if isinstance(v, (int, bool)) or v is None:
                result[k] = v
        elif k in ('errDesc', 'errorDesc', 'description', 'message') and isinstance(v, str):
            # Fixed public error descriptions, never arbitrary server strings.
            known = {'成功', '不支持此服务', '操作成功', 'success', 'ok',
                     'service not available', 'invalid parameter', 'invalid token',
                     'permission denied', '不支持', '服务不可用', '参数错误',
                     '无效参数', '没有权限', '系统错误', '系统繁忙'}
            if v in known:
                result[k] = v
            else:
                result[k + '_keywords'] = [word for word in
                    ('transfer', 'unsupported', 'permission', 'auth', 'invalid',
                     'token', 'cplc', 'device', '参数', '权限', '支持', '迁移', '移卡')
                    if word in v.lower()]
        elif isinstance(v, bool) and any(s in k.lower() for s in ('transfer', 'shift', 'support', 'issued')):
            result[k] = v
        elif k in ('cardName', 'cardType') and isinstance(v, str):
            if len(v) < 80 and all(c.isalpha() or c == '_' for c in v):
                result[k] = v
        elif k in ('title', 'issuerName') and isinstance(v, str) and len(v) < 120:
            result[k] = v
        elif isinstance(v, (dict, list)):
            result[k] = public_result(v)
    return result


def query(session, command):
    plan_name = None
    if isinstance(command, dict) and set(command) == {'plan_file'}:
        # Device identifiers may be prepared privately without echoing them
        # through terminal/tool transcripts. This file never contains tokens.
        plan = Path(command['plan_file']).resolve()
        base = Path('/tmp/.agents/gadgetbridge-transit-research/tsm-auth-live').resolve()
        if not plan.is_relative_to(base) or stat.S_IMODE(plan.stat().st_mode) != 0o600:
            raise ValueError('query plan must be private and in the task directory')
        command = json.loads(plan.read_text())
        plan_name = plan.name
    path, params = validate(command)
    url, body, cookie = session.prepare_read_only(path, params, modern=(path in (ACL, ISSUED)))
    row = {'method': 'POST', 'path': path,
           'observed_at_utc': datetime.now(timezone.utc).isoformat(),
           'request_fields': sorted(params), 'credential_values_recorded': False,
           'request_context': {k: params[k] for k in ('cardName', 'cardActionType',
                               'deviceModel', 'appPackageName', 'firmwareVersion',
                               'appVersionCode', 'appVersionName', 'tsmclientVersionCode',
                               'tsmclientVersionName', 'miuiRomType', 'miuiSystemVersion',
                               'androidVersion', 'supportCardTransfer', 'type') if k in params},
           'cplc_length': len(params['cplc']), 'cplc_full_recorded': False}
    if plan_name:
        row['plan_name'] = plan_name
    request = urllib.request.Request(url, data=body, method='POST', headers={
        'Cookie': cookie, 'Content-Type': 'application/x-www-form-urlencoded',
        'Accept': 'application/json', 'User-Agent': 'Gadgetbridge-TSM-Research/1.0'})
    opener = urllib.request.build_opener(NoRedirects())
    start = time.monotonic()
    try:
        try:
            response = opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            row['http_status'] = response.status
            raw = response.read(2_000_001)
    except Exception as error:
        row.update(http_status=None, transport_error_type=type(error).__name__)
        return row
    row['http_round_trip_ms'] = round((time.monotonic() - start) * 1000)
    if len(raw) > 2_000_000:
        row['result'] = 'response_size_limit'
        return row
    try:
        text = raw.decode().removeprefix('&&&START&&&')
        try:
            data = json.loads(text)
            row['response_encoding'] = 'json'
        except ValueError:
            data = json.loads(decrypt(text.strip(), session.security))
            row['response_encoding'] = 'session_aes_json'
        row['response_shape'] = response_shape(data)
        row['response_status_and_eligibility'] = public_result(data)
        if path == ISSUED and isinstance(data, dict) and isinstance(data.get('data'), list):
            row['issued_card_checks'] = []
            for item in data['data']:
                if not isinstance(item, dict):
                    continue
                number = item.get('faceNo')
                row['issued_card_checks'].append({
                    'matches_requested_product': item.get('cardName') == params['cardName'],
                    'matches_requested_cplc': str(item.get('cplc', '')).upper() == params['cplc'].upper(),
                    'matches_approved_account': str(item.get('userId', '')) == session.user_id,
                    'face_number_last4': number[-4:] if isinstance(number, str) and
                        len(number) > 4 and number.isascii() and number.isdigit() else None})
    except Exception as error:
        row['response_parse_error_type'] = type(error).__name__
    return row
