# SPDX-License-Identifier: AGPL-3.0-or-later
import unittest

from routing_table import TEST_ROUTE, parse_notifications, plan, set_routing_command

REAL_S4_NOTIFICATION = bytes.fromhex('61020c00020003c0e3000003c0e302')


class RoutingBackupTest(unittest.TestCase):
    def test_real_snapshot_preserves_all_original_bytes(self):
        p = plan([REAL_S4_NOTIFICATION])
        self.assertEqual(p['original_tlvs_hex'], '0003c0e3000003c0e302')
        self.assertEqual(p['install_command_hex'],
                         '210117000302090001f00102030405060003c0e3000003c0e302')
        self.assertEqual(p['restore_command_hex'], '21010c00020003c0e3000003c0e302')
        self.assertTrue(p['original_tlvs_and_order_preserved'])

    def test_segmented_notification(self):
        frames = [bytes.fromhex('61020701010003c0e300'), bytes.fromhex('61020700010003c0e302')]
        self.assertEqual(parse_notifications(frames), parse_notifications([REAL_S4_NOTIFICATION]))

    def test_unknown_route_is_lossless_backup_but_no_write_plan(self):
        frame = bytes.fromhex('6102050001ee01ab')
        entries = parse_notifications([frame])
        self.assertEqual(set_routing_command(entries).hex(), '2101050001ee01ab')
        with self.assertRaisesRegex(ValueError, 'opaque'):plan([frame])

    def test_bad_notification_refused(self):
        variants = [[], [REAL_S4_NOTIFICATION[:-1]],
                    [bytes.fromhex('6102020100')],
                    [REAL_S4_NOTIFICATION, REAL_S4_NOTIFICATION],
                    [bytes.fromhex('61020400010003')],
                    [bytes.fromhex('610204000100ff')],
                    [bytes.fromhex('61020c00010003c0e3000003c0e302')]]
        for frames in variants:
            with self.subTest(frames=frames), self.assertRaises(ValueError):
                parse_notifications(frames)

    def test_preexisting_test_aid_not_claimed(self):
        frame = bytes((0x61, 2, len(TEST_ROUTE)+2, 0, 1))+TEST_ROUTE
        with self.assertRaisesRegex(ValueError, 'already present'):plan([frame])

    def test_oversized_single_write_refused(self):
        with self.assertRaisesRegex(ValueError, 'segmented'):
            set_routing_command([bytes.fromhex('eeff')+bytes(255)])


if __name__ == '__main__':unittest.main()
