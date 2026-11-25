# SPDX-License-Identifier: AGPL-3.0-or-later
"""Captured wire responses and malformed-frame rejection; no device required."""
import unittest
from run import decode_response, fields


class CapturedResponses(unittest.TestCase):
    def test_capability(self):
        self.assertEqual({'capability': 1}, decode_response('0805100A3A025001', True))

    def test_open_and_close_envelope(self):
        self.assertEqual({'status': 224, 'data': ''}, decode_response('080510003A080A06000000E00000'))

    def test_unknown_test_aid(self):
        self.assertEqual({'status': 224, 'data': '6A82'}, decode_response('080510003A0A0A08000000E000026A82'))

    def test_malformed_frames(self):
        for raw in ('80', '00', '08050805', '3A0800', '0D00000000'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                fields(bytes.fromhex(raw))

    def test_wrong_command(self):
        with self.assertRaises(ValueError):
            decode_response('080410003A080A06000000E00000')

    def test_envelope_length_mismatch(self):
        with self.assertRaises(ValueError):
            decode_response('080510003A0A0A08000000E000036A82')


if __name__ == '__main__':
    unittest.main()
