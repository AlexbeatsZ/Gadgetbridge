#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Independent user-approved QR/STS authentication, without copying app tokens.

No password, existing passToken, account database, existing browser cookies or
system whitelist is read. The official account service issues a fresh session after
the user scans and approves its QR challenge. Challenges/tokens are never
printed. Optional interactive queries are limited to verified read-only routes.
"""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from tsm_protocol import LoginSession, signature

ACCOUNT = 'https://account.xiaomi.com'
ALLOWED_HOSTS = frozenset({'account.xiaomi.com', 'ak.account.xiaomi.com',
                           'ak.lp.account.xiaomi.com', 'c3.account.xiaomi.com',
                           'c3.lp.account.xiaomi.com', 'tsmapi.pay.xiaomi.com'})


class AuthError(Exception):
    """Fixed diagnostic message only; never wrap raw URLs/HTTP exceptions."""


class PollTimeout(AuthError):
    pass


def checked_url(url):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != 'https' or parts.hostname not in ALLOWED_HOSTS or parts.username or parts.password:
        raise AuthError('unapproved authentication origin')
    if parts.port not in (None, 443):
        raise AuthError('unapproved authentication port')
    return url


class RedirectGuard(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        checked_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Passport:
    def __init__(self, evidence):
        self.cookies = http.cookiejar.CookieJar()  # memory only
        self.http = urllib.request.build_opener(RedirectGuard(),
                       urllib.request.HTTPCookieProcessor(self.cookies))
        self.evidence = evidence

    def get(self, label, url, *, timeout=20):
        checked_url(url)
        request = urllib.request.Request(url, headers={
            'User-Agent': 'Gadgetbridge-TSM-Research/1.0', 'Accept': 'application/json'})
        try:
            response = self.http.open(request, timeout=timeout)
            with response:
                status, headers, body = response.status, response.headers, response.read(2_000_001)
        except urllib.error.HTTPError as response:
            # HTTPError is also a response, but never stringify its secret URL.
            with response:
                status, headers, body = response.code, response.headers, response.read(2_000_001)
        except TimeoutError:
            raise PollTimeout('authentication request timed out') from None
        except urllib.error.URLError as failure:
            if isinstance(failure.reason, TimeoutError):
                raise PollTimeout('authentication request timed out') from None
            self.evidence.append({'step': label, 'transport_error_type': type(failure.reason).__name__, 'http_status': None})
            raise AuthError('authentication transport failed') from None
        except AuthError:
            raise
        except Exception as failure:
            self.evidence.append({'step': label, 'transport_error_type': type(failure).__name__, 'http_status': None})
            raise AuthError('authentication transport failed') from None
        if len(body) > 2_000_000:
            raise AuthError('authentication response exceeds limit')
        row = {'step': label, 'http_status': status,
               'origin': urllib.parse.urlsplit(url).hostname,
               'request_query_keys': sorted(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)),
               'cookie_names': sorted({c.name for c in self.cookies
                                       if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_-]*', c.name)})}
        self.evidence.append(row)
        return body, headers, row

    def json_get(self, label, url, **kwargs):
        body, headers, row = self.get(label, url, **kwargs)
        try:
            data = json.loads(body.decode().removeprefix('&&&START&&&'))
        except (ValueError, UnicodeError):
            raise AuthError('authentication response is not JSON') from None
        if not isinstance(data, dict):
            raise AuthError('authentication response is not an object')
        row['response_fields'] = sorted(data)
        row['code'] = data.get('code') if isinstance(data.get('code'), int) else None
        return data, headers

    def bootstrap_qr(self):
        data, _ = self.json_get('qr_bootstrap', ACCOUNT + '/longPolling/loginUrl?sid=tsm-auth')
        if data.get('code') != 0:
            raise AuthError('QR authorization bootstrap refused')
        # Official display instructions contain no challenge or credentials.
        self.evidence[-1]['qr_tips'] = data.get('qrTips') if isinstance(data.get('qrTips'), str) else None
        login = checked_url(data['loginUrl'])
        poll = checked_url(data['lp'])
        return login, poll, min(int(data.get('timeout', 180)), 300)

    def await_approval(self, poll_url, *, seconds=180):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                data, headers = self.json_get('qr_poll', poll_url,
                                             timeout=min(55, max(1, deadline-time.monotonic())))
            except PollTimeout:
                self.evidence.append({'step': 'qr_poll', 'result': 'client_wait_timeout',
                                      'http_status': None})
                continue
            except AuthError as failure:
                # Do not retry unknown responses, consent failures or risk checks.
                raise failure from None
            if data.get('code') == 0:
                if data.get('securityStatus', 0) != 0:
                    raise AuthError('official additional account verification required')
                return self.exchange_sts(data, headers)
            # Codes have not been verified for this endpoint/version. Preserve
            # the sanitized code and stop instead of guessing a polling state.
            raise AuthError('QR approval pending or refused; see sanitized code')
        raise AuthError('QR approval timed out')

    def exchange_sts(self, data, headers):
        security, nonce, location = data.get('ssecurity'), data.get('nonce'), data.get('location')
        if not security or nonce is None:
            extension = headers.get('extension-pragma') or headers.get('Extension-Pragma')
            if extension:
                try:
                    extra = json.loads(extension)
                    security, nonce = extra.get('ssecurity'), extra.get('nonce')
                except ValueError:
                    raise AuthError('invalid STS security metadata') from None
        if not security or nonce is None or not location:
            raise AuthError('STS prerequisites missing')
        checked_url(location)
        user_id = data.get('userId') or self.cookie('userId', 'account.xiaomi.com')
        if not user_id:
            raise AuthError('approved user identity missing')
        query = {'clientSign': signature(None, None, {'nonce': str(nonce)}, security),
                 '_userIdNeedEncrypt': 'true'}
        url = location + ('&' if '?' in location else '?') + urllib.parse.urlencode(query)
        _, _, _ = self.get('sts_exchange', url)
        token = self.cookie('tsm-auth_serviceToken', 'tsmapi.pay.xiaomi.com') or self.cookie('serviceToken', 'tsmapi.pay.xiaomi.com')
        if not token:
            raise AuthError('STS did not issue a TSM service token')
        return LoginSession(str(user_id), token, security,
                            self.cookie('tsm-auth_ph', 'tsmapi.pay.xiaomi.com') or '')

    def cookie(self, name, host):
        for cookie in self.cookies:
            domain = cookie.domain.lstrip('.')
            if cookie.name == name and (host == domain or host.endswith('.' + domain)):
                return cookie.value
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--qr-image', type=Path, help='temporary QR challenge image; removed on exit')
    parser.add_argument('--approve-window', type=int, default=180)
    parser.add_argument('--interactive', action='store_true',
                        help='keep approved session in memory for allowlisted read-only queries on stdin')
    args = parser.parse_args()
    evidence = {'credential_values_recorded': False, 'card_http_requests': 0, 'steps': []}
    passport = Passport(evidence['steps'])
    qr_written = False
    try:
        if args.qr_image is None:
            passport.json_get('service_login_bootstrap', ACCOUNT + '/pass/serviceLogin?sid=tsm-auth&_json=true')
            passport.bootstrap_qr()
            evidence['result'] = 'BOOTSTRAP_ONLY_NO_USER_AUTHORIZATION'
        else:
            import qrcode
            login, poll, lifetime = passport.bootstrap_qr()
            args.qr_image.parent.mkdir(parents=True, exist_ok=True)
            # Never overwrite/delete an existing unrelated image. Create the
            # official short-lived challenge with private permissions at once.
            fd = os.open(args.qr_image, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            qr_written = True
            with os.fdopen(fd, 'wb') as qr:
                qrcode.make(login).save(qr, format='PNG')
            print('QR_READY ' + str(args.qr_image), flush=True)
            session = passport.await_approval(poll, seconds=min(args.approve_window, lifetime))
            args.qr_image.unlink(missing_ok=True)
            # Only the TSM session is needed now. Discard fresh account SSO
            # cookies too; never repurpose passToken for unrelated services.
            passport.cookies.clear()
            evidence['result'] = 'TSM_SESSION_ISSUED_NO_CARD_QUERY'
            evidence['session_issued'] = True
            if args.interactive:
                # No session serialization. A persistent process lets the user
                # approve once, while verified device metadata is prepared.
                print('AUTHORIZATION_READY_SESSION_IN_MEMORY', flush=True)
                for line in sys.stdin:
                    if line.strip() == 'close':
                        break
                    try:
                        command = json.loads(line)
                        # Research transport may be refined while the user
                        # completes consent; retain the same memory session.
                        import importlib
                        import tsm_protocol
                        import read_only
                        importlib.reload(tsm_protocol)
                        importlib.reload(read_only)
                        from read_only import query
                        row = query(session, command)
                        evidence.setdefault('queries', []).append(row)
                        evidence['card_http_requests'] += 1
                        evidence['result'] = 'AUTHORIZED_READ_ONLY_RESEARCH'
                        print(json.dumps({'query': row}, ensure_ascii=False), flush=True)
                    except Exception as failure:
                        # Never stringify exceptions that may include secrets.
                        print(json.dumps({'query_error_type': type(failure).__name__}), flush=True)
            del session
    except AuthError as failure:
        evidence['result'] = str(failure)
    except Exception:
        evidence['result'] = 'UNEXPECTED_AUTH_PROTOCOL_ERROR'
    finally:
        passport.cookies.clear()
        if qr_written:
            args.qr_image.unlink(missing_ok=True)
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(evidence, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
