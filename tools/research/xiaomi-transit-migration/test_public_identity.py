# SPDX-License-Identifier: AGPL-3.0-or-later
import unittest
from public_identity import fudan_metadata, normalize_lookup_number


class PublicIdentityTests(unittest.TestCase):
    def test_11_and_19_digit_lookup_forms_compare_equal(self):
        short = '00000000001'  # Synthetic fixture, not a real card.
        self.assertEqual(normalize_lookup_number(short), normalize_lookup_number('31047700' + short))

    def test_lookup_rejects_tail_cplc_uid_and_non_ascii_digits(self):
        for value in ('8213', 'TEST_CPLC', '04AABBCC', '０' * 11, '1' * 12, 1, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalize_lookup_number(value)

    def test_exact_university_match_and_metadata_allowlist(self):
        catalog = {'cobrandList': [
            {'coBrandCardName': '复旦大学附属复兴中学', 'coBrandCard': 'TEST_OTHER_PRODUCT'},
            {'coBrandCardName': '复旦大学', 'coBrandCard': 'SPTC.MOT.SH.FDU',
             'partnerNo': '06000047', 'userToken': 'TEST_MUST_NOT_LEAK',
             'openCardInfo': {'xySummary': 'TEST_PUBLIC_SUMMARY', 'password': 'TEST_MUST_NOT_LEAK'}},
        ]}
        result = fudan_metadata(catalog)
        self.assertEqual(result['coBrandCard'], 'SPTC.MOT.SH.FDU')
        self.assertEqual(result['partnerNo'], '06000047')
        self.assertNotIn('TEST_MUST_NOT_LEAK', str(result))

    def test_missing_or_duplicate_university_metadata_is_not_guessed(self):
        for catalog in ([], [{'coBrandCardName': '复旦大学'}] * 2):
            with self.assertRaises(ValueError):
                fudan_metadata(catalog)


if __name__ == '__main__':
    unittest.main()
