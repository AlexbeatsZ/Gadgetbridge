# SPDX-License-Identifier: AGPL-3.0-or-later
import base64
import json
import unittest
from urllib.parse import parse_qs
from unittest.mock import patch

from tsm_protocol import LoginSession, decrypt, encrypt, encrypt_params, response_shape, signature
from passport import AuthError, Passport, PollTimeout, checked_url

# Synthetic key and strings only. Independently checked against the inspected
# AccountSDK Java SecureRequest/Coder and javax.crypto implementation.
SECURITY = 'AAECAwQFBgcICQoLDA0ODw=='
URL = 'https://tsmapi.pay.xiaomi.com/api/login/acl/checkServiceAvailable'
PLAIN = {'userId': 'test-user', 'deviceModel': 'Xiaomi Watch S4',
         'note': '复旦/测试 & +', '_nonce': 'public-test'}
GOLDEN = {'userId': 'zEB9yv+BEUSi3SyCTjDL8Q==',
          'deviceModel': 'P9xQRaJKfEL1m7s4QhpRAA==',
          'note': 'mJhDFrXbPRGNx/qJNXojjtjOAiC5jvJL6UmH0008/8c=',
          '_nonce': 'public-test', 'signature': '3kw5Oy1GLkWgowZlzkkNVKvapn8='}


class WireTests(unittest.TestCase):
    def test_accountsdk_golden_vector(self):
        self.assertEqual(encrypt_params('POST', URL, PLAIN, SECURITY), GOLDEN)
        for key in ('userId', 'deviceModel', 'note'):
            self.assertEqual(decrypt(GOLDEN[key], SECURITY), PLAIN[key])

    def test_block_boundaries_utf8_and_underscore(self):
        for text in ('', 'a'*15, 'a'*16, 'a'*17, '复旦大学'):
            self.assertEqual(decrypt(encrypt(text, SECURITY), SECURITY), text)
        self.assertEqual(encrypt_params('POST', URL, {'_x': 'clear', 'x': None}, SECURITY)['_x'], 'clear')

    def test_signature_uses_sorted_ciphertext_and_encoded_path(self):
        ordered = dict(reversed(list(PLAIN.items())))
        self.assertEqual(encrypt_params('post', URL, ordered, SECURITY), GOLDEN)
        a = signature('POST', URL+'?ignored=1', {'x': '+/='}, SECURITY)
        self.assertEqual(a, signature('POST', URL, {'x': '+/='}, SECURITY))
        self.assertNotEqual(a, signature('POST', URL.replace('/acl/', '/%61cl/'), {'x': '+/='}, SECURITY))

    def test_write_and_se_session_routes_rejected(self):
        session = LoginSession('synthetic-id', 'synthetic-token', SECURITY)
        for path in ('/api/login/transferCard/prepareTransferOut', '/api/login/se/createSession',
                     '/api/login/se/startSeOperation', '/api/login/spcard/v2/queryShiftInCardProducts?x=1',
                     'https://other.example/api/login/acl/checkServiceAvailable'):
            with self.assertRaises(ValueError): session.prepare_read_only(path, {})

    def test_transaction_parameters_rejected_before_network(self):
        session = LoginSession('synthetic-id', 'synthetic-token', SECURITY)
        for key in ('orderId', 'authentication_code', 'transferOutToken', 'tsmSessionId', 'token', 'signature', 'userId'):
            with self.assertRaises(ValueError):
                session.prepare_read_only('/api/login/acl/checkServiceAvailable', {key: 'test'})

    def test_modern_and_legacy_envelopes_stay_distinct(self):
        session = LoginSession('synthetic-id', 'synthetic-token', SECURITY, 'synthetic-ph')
        path = '/api/login/acl/checkServiceAvailable'
        _, body, cookies = session.prepare_read_only(path, {'cardActionType': 'TRANSFER_OUT'}, modern=True)
        fields = parse_qs(body.decode())
        self.assertEqual(decrypt(fields['token'][0], SECURITY), 'synthetic-token,'+SECURITY)
        self.assertIn('tsm-auth_ph=synthetic-ph', cookies)
        _, body, _ = session.prepare_read_only('/api/login/spcard/v2/queryShiftInCardProducts', {})
        self.assertNotIn('token', parse_qs(body.decode()))

    def test_no_credential_repr_or_response_values(self):
        self.assertEqual(repr(LoginSession('secret', 'secret', SECURITY)), 'LoginSession(<redacted>)')
        self.assertNotIn('sensitive-card', json.dumps(response_shape({'cardNumber': 'sensitive-card'})))

    def test_invalid_key_and_padding_fail_closed(self):
        with self.assertRaises(ValueError): encrypt('x', base64.b64encode(b'short').decode())
        with self.assertRaises(ValueError): decrypt('AAAA', SECURITY)


class AuthorizationTests(unittest.TestCase):
    def test_origin_restrictions(self):
        for url in ('http://account.xiaomi.com/', 'https://account.xiaomi.com.attacker.example/',
                    'https://user:password@account.xiaomi.com/', 'https://account.xiaomi.com:8443/'):
            with self.assertRaises(AuthError): checked_url(url)

    def test_transport_timeout_does_not_infer_authorization(self):
        rows = []
        client = Passport(rows)
        with patch.object(client, 'json_get', side_effect=[PollTimeout('wait'), ({'code': 70016}, {})]):
            with self.assertRaisesRegex(AuthError, 'pending or refused'):
                client.await_approval('https://ak.lp.account.xiaomi.com/', seconds=1)
        self.assertEqual(rows[0]['http_status'], None)

    def test_risk_verification_never_exchanges_sts(self):
        client = Passport([])
        with patch.object(client, 'json_get', return_value=({'code': 0, 'securityStatus': 1}, {})), \
             patch.object(client, 'exchange_sts') as sts:
            with self.assertRaises(AuthError): client.await_approval('https://ak.lp.account.xiaomi.com/', seconds=1)
            sts.assert_not_called()


if __name__ == '__main__':
    unittest.main()
