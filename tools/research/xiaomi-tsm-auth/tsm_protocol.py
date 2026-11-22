#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""TSM login-mode wire codec. Credentials stay in memory; no credential CLI flags.

Derived from the inspected Mi Fitness 3.61.0 AccountSDK SecureRequest/Coder
behavior. This is distinct from public OAuth MAC-token authentication.
"""
import base64
import hashlib
from dataclasses import dataclass, field
from urllib.parse import urlencode, urlsplit

ORIGIN = 'https://tsmapi.pay.xiaomi.com'
READ_ONLY_PATHS = frozenset({
    '/api/login/acl/checkServiceAvailable',
    '/api/login/spcard/v2/queryShiftInCardProducts',
})
IV = b'0102030405060708'


def signature(method, url, params, security):
    """Base64(SHA1(METHOD&encoded_path&sorted(encrypted k=v)&security))."""
    if not security:
        raise ValueError('security required')
    parts = []
    if method is not None:
        parts.append(method.upper())
    if url is not None:
        parts.append(urlsplit(url).path)
    parts.extend(f'{key}={params[key]}' for key in sorted(params))
    parts.append(security)
    return base64.b64encode(hashlib.sha1('&'.join(parts).encode()).digest()).decode()


def _cipher(security):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    key = base64.b64decode(security, validate=True)
    if len(key) != 16:
        raise ValueError('expected AccountSDK 128-bit AES security')
    return Cipher(algorithms.AES(key), modes.CBC(IV))


def encrypt(value, security):
    from cryptography.hazmat.primitives import padding
    padder = padding.PKCS7(128).padder()
    padded = padder.update(value.encode()) + padder.finalize()
    encoder = _cipher(security).encryptor()
    return base64.b64encode(encoder.update(padded) + encoder.finalize()).decode()


def decrypt(value, security):
    from cryptography.hazmat.primitives import padding
    decoder = _cipher(security).decryptor()
    padded = decoder.update(base64.b64decode(value, validate=True)) + decoder.finalize()
    unpadder = padding.PKCS7(128).unpadder()
    return (unpadder.update(padded) + unpadder.finalize()).decode()


def encrypt_params(method, url, params, security):
    result = {key: value if key.startswith('_') else encrypt(value, security)
              for key, value in params.items() if key is not None and value is not None}
    result['signature'] = signature(method, url, result, security)
    return result


@dataclass(repr=False)
class LoginSession:
    user_id: str = field(repr=False)
    service_token: str = field(repr=False)
    security: str = field(repr=False)
    ph: str = field(default='', repr=False)

    def __repr__(self):
        return 'LoginSession(<redacted>)'

    def prepare_read_only(self, path, params, *, modern=False):
        """Fail closed before transport; writes and SE-session creation unavailable.

        queryShiftInCardProducts uses the legacy TSMAuthClient envelope. The
        acl request uses the newer interceptor which also puts token/ph in form.
        Caller supplies honest device/app metadata and the validated card name.
        """
        if path not in READ_ONLY_PATHS:
            raise ValueError('endpoint not in the read-only research allowlist')
        if any(name in params for name in ('orderId', 'authentication_code', 'transferOutToken', 'tsmSessionId')):
            raise ValueError('transaction/session parameters are outside this module')
        if 'userId' in params or 'token' in params or 'signature' in params:
            raise ValueError('authentication parameters belong to the session')
        clean = dict(params, userId=self.user_id)
        cookies = {'userId': self.user_id, 'serviceToken': self.service_token}
        if modern:
            clean['token'] = self.service_token + ',' + self.security
            if self.ph:
                clean['tsm-auth_ph'] = self.ph
                cookies['tsm-auth_ph'] = self.ph
        url = ORIGIN + path
        body = urlencode(encrypt_params('POST', url, clean, self.security)).encode()
        # Return sensitive material only to an in-process HTTPS transport.
        return url, body, '; '.join(f'{k}={v}' for k, v in cookies.items())


def response_shape(value):
    """Record field names, container shapes and counts; never arbitrary values."""
    if isinstance(value, dict):
        return {str(k): response_shape(v) for k, v in value.items()}
    if isinstance(value, list):
        return {'type': 'array', 'count': len(value),
                'item_shapes': [response_shape(v) for v in value[:3]]}
    if value is None:
        return 'null'
    if isinstance(value, bool):
        return 'boolean'
    if isinstance(value, (int, float)):
        return 'number'
    return 'string(redacted)'
