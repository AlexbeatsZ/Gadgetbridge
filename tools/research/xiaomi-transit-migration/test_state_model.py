# SPDX-License-Identifier: AGPL-3.0-or-later
from collections import Counter
from dataclasses import fields, replace
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from state_model import (APDU_ACTIONS, STEPS, Binding, Coordinator, Journal,
                         Preconditions, SimulationBackend, WRITE_ACTIONS)


def approved():
    # A synthetic fixture, never evidence of actual campus preservation.
    return Preconditions(write_authorized=True, campus_preservation_confirmed=True)


class MigrationModelTests(unittest.TestCase):
    def test_default_unknown_campus_stops_before_all_mutations(self):
        backend = SimulationBackend()
        model = Coordinator(backend, Preconditions(write_authorized=True))
        self.assertEqual(model.run(), 'BLOCKED')
        self.assertIn('campus_preservation_confirmed', model.journal.reason)
        self.assertEqual(backend.calls, [])
        self.assertTrue(backend.source_usable)

    def test_every_missing_precondition_blocks(self):
        for item in fields(Preconditions):
            with self.subTest(item=item.name):
                backend = SimulationBackend()
                model = Coordinator(backend, replace(approved(), **{item.name: False}))
                self.assertEqual(model.run(), 'BLOCKED')
                self.assertFalse(backend.calls)

    def test_success_requires_target_and_campus_checks(self):
        backend = SimulationBackend()
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'COMPLETE')
        self.assertFalse(backend.source_usable)
        self.assertTrue(backend.target_issued)
        self.assertEqual(backend.calls, [name for name, _ in STEPS])
        self.assertEqual(backend.calls[-2:], ['query_target', 'query_campus'])

    def test_known_applied_http_outcome_does_not_duplicate_mutation(self):
        # Fake oracle only: not an assertion that any real HTTP write is idempotent.
        for action in WRITE_ACTIONS - APDU_ACTIONS:
            with self.subTest(action=action):
                backend = SimulationBackend()
                backend.fault_once(action, 'after')
                model = Coordinator(backend, approved())
                self.assertEqual(model.run(), 'RECONCILE')
                model.recover()
                self.assertEqual(model.run(), 'COMPLETE')
                self.assertEqual(Counter(backend.calls)[action], 1)

    def test_lost_apdu_response_never_replays_apdu(self):
        for action in APDU_ACTIONS:
            with self.subTest(action=action):
                backend = SimulationBackend()
                backend.fault_once(action, 'after')
                model = Coordinator(backend, approved())
                self.assertEqual(model.run(), 'RECONCILE')
                model.recover()
                self.assertEqual(model.run(), 'HOLD')
                self.assertEqual(Counter(backend.calls)[action], 1)
                self.assertIn('response missing', model.journal.reason)

    def test_crash_after_side_effect_retains_durable_intent(self):
        class SimulatedProcessCrash(BaseException):
            pass
        backend = SimulationBackend()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'journal.json'
            model = Coordinator(backend, approved(), journal_path=path)
            while STEPS[model.journal.position][0] != 'write_out_scripts':
                model.step()
            original = backend.perform
            def crash(action, intent):
                original(action, intent)
                raise SimulatedProcessCrash()
            with patch.object(backend, 'perform', side_effect=crash):
                with self.assertRaises(SimulatedProcessCrash):
                    model.step()
            saved = Journal.load(path)
            self.assertEqual(saved.inflight['action'], 'write_out_scripts')
            self.assertFalse(backend.source_usable)
            resumed = Coordinator(backend, approved(), saved, path)
            resumed.step()
            self.assertEqual(resumed.journal.status, 'RECONCILE')
            resumed.recover()
            self.assertEqual(resumed.journal.status, 'HOLD')
            self.assertEqual(backend.calls.count('write_out_scripts'), 1)

    def test_unknown_server_reconciliation_never_invents_rollback(self):
        backend = SimulationBackend()
        backend.fault_once('confirm_out', 'after')
        model = Coordinator(backend, approved())
        model.run()
        backend.uncertain.add(model.journal.inflight['intent'])
        model.recover()
        self.assertEqual(model.journal.status, 'HOLD')
        self.assertFalse(backend.source_usable)
        self.assertTrue(backend.cloud_ready)
        self.assertNotIn('start_in_order', backend.calls)
        self.assertFalse(any('rollback' in x or 'refund' in x for x in backend.calls))

    def test_authoritative_not_applied_can_retry_existing_step(self):
        backend = SimulationBackend()
        backend.fault_once('prepare_out', 'before')
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'RECONCILE')
        self.assertFalse(backend.applied.get(model.journal.inflight['intent']))
        model.recover()
        self.assertEqual(model.run(), 'COMPLETE')
        self.assertEqual(backend.calls.count('prepare_out'), 2)

    def test_read_failure_only_retries_read(self):
        backend = SimulationBackend()
        backend.fault_once('query_cloud', 'before')
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'READ_RETRY')
        model.recover()
        self.assertEqual(model.run(), 'COMPLETE')
        self.assertEqual(backend.calls.count('query_cloud'), 2)
        self.assertEqual(backend.calls.count('prepare_out'), 1)
        self.assertEqual(backend.calls.count('write_out_scripts'), 1)

    def test_data_not_ready_is_bounded_and_does_not_recreate_order(self):
        backend = SimulationBackend()
        for _ in range(3):
            backend.fault_once('pull_out_scripts', 'pending')
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'HOLD')
        self.assertTrue(backend.source_usable)
        self.assertEqual(backend.calls.count('prepare_out'), 1)
        self.assertNotIn('write_out_scripts', backend.calls)
        self.assertEqual(backend.calls.count('pull_out_scripts'), 3)

    def test_report_retry_after_restart_uses_identical_saved_responses(self):
        backend = SimulationBackend()
        backend.fault_once('report_out', 'before')
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'journal.json'
            model = Coordinator(backend, approved(), journal_path=path)
            self.assertEqual(model.run(), 'RECONCILE')
            saved = Journal.load(path)
            self.assertEqual(saved.script_receipts['out:0'], backend.report_payloads[0])
            resumed = Coordinator(backend, approved(), saved, path)
            resumed.recover()  # Only the fake oracle proves NOT_APPLIED.
            self.assertEqual(resumed.run(), 'COMPLETE')
            self.assertEqual(backend.calls.count('write_out_scripts'), 1)
            self.assertEqual(backend.report_payloads[0], backend.report_payloads[1])

    def test_missing_saved_response_stops_report_without_replaying_apdu(self):
        backend = SimulationBackend()
        model = Coordinator(backend, approved())
        while STEPS[model.journal.position][0] != 'report_out':
            model.step()
        model.journal.script_receipts.clear()
        model.step()
        self.assertEqual(model.journal.status, 'HOLD')
        self.assertNotIn('report_out', backend.calls)
        self.assertEqual(backend.calls.count('write_out_scripts'), 1)

    def test_multiple_batches_finish_reports_before_pulling_next_batch(self):
        backend = SimulationBackend(batches=3)
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'COMPLETE')
        for direction in ('out', 'in'):
            actions = [x for x in backend.calls if x in
                       (f'pull_{direction}_scripts', f'write_{direction}_scripts', f'report_{direction}')]
            self.assertEqual(actions, [f'pull_{direction}_scripts',
                                      f'write_{direction}_scripts', f'report_{direction}'] * 3)
        self.assertEqual(len({x['scripts_id'] for x in backend.report_payloads}), 6)
        self.assertEqual(backend.calls.count('prepare_out'), 1)
        self.assertEqual(backend.calls.count('start_in_order'), 1)

    def test_report_rejects_a_response_from_another_batch_or_session(self):
        backend = SimulationBackend()
        for receipt in (None, {'session_id': 'TEST_OTHER_SESSION'}, backend._script_receipt('in')):
            with self.assertRaises(ValueError):
                backend.perform('report_out', 'TEST_INTENT', receipt)

    def test_payment_requirement_stops_before_source_shift(self):
        backend = SimulationBackend()
        backend.payment_required = True
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'HOLD')
        self.assertNotIn('start_out_operation', backend.calls)
        self.assertTrue(backend.source_usable)

    def test_recovery_rejects_changed_account_product_or_se(self):
        for item in fields(Binding):
            with self.subTest(item=item.name):
                backend = SimulationBackend()
                backend.fault_once('confirm_out', 'after')
                model = Coordinator(backend, approved())
                model.run()
                before = len(backend.calls)
                backend.binding = replace(backend.binding, **{item.name: 'TEST_CHANGED'})
                model.recover()
                self.assertEqual(model.journal.status, 'HOLD')
                self.assertEqual(len(backend.calls), before)

    def test_changed_binding_before_write_stops(self):
        backend = SimulationBackend()
        model = Coordinator(backend, approved())
        model.step()
        backend.binding = replace(backend.binding, target_se='TEST_OTHER_WATCH')
        model.step()
        self.assertEqual(model.journal.status, 'HOLD')
        self.assertNotIn('precheck_out', backend.calls)

    def test_authorization_loss_before_next_write_stops(self):
        backend = SimulationBackend()
        model = Coordinator(backend, approved())
        model.step()
        model.preconditions = replace(approved(), account_authorized=False)
        model.step()
        self.assertEqual(model.journal.status, 'BLOCKED')
        self.assertNotIn('precheck_out', backend.calls)

    def test_cloud_lineage_mismatch_stops_before_start_in(self):
        for field_name in ('cloud_product_matches', 'cloud_lineage_matches'):
            with self.subTest(field_name=field_name):
                backend = SimulationBackend()
                setattr(backend, field_name, False)
                model = Coordinator(backend, approved())
                self.assertEqual(model.run(), 'HOLD')
                self.assertNotIn('start_in_order', backend.calls)

    def test_source_owner_mismatch_stops_before_precheck(self):
        backend = SimulationBackend()
        backend.owner_matches = False
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'HOLD')
        self.assertNotIn('precheck_out', backend.calls)

    def test_bad_sw_prevents_confirm_and_inbound(self):
        backend = SimulationBackend()
        backend.sw_ok = False
        model = Coordinator(backend, approved())
        self.assertEqual(model.run(), 'HOLD')
        self.assertNotIn('confirm_out', backend.calls)
        self.assertFalse(backend.target_issued)
        self.assertFalse(model.journal.script_receipts['out:0']['action_result'])
        self.assertEqual(model.journal.receipts[-1]['action'], 'write_out_scripts')
        self.assertIsNone(model.journal.inflight)

    def test_target_numbers_balance_and_campus_verified_separately(self):
        for name in ('target_numbers_match', 'se_balance_ok', 'campus_valid'):
            with self.subTest(name=name):
                backend = SimulationBackend()
                setattr(backend, name, False)
                model = Coordinator(backend, approved())
                self.assertEqual(model.run(), 'HOLD')
                self.assertTrue(backend.target_issued)
                self.assertNotEqual(model.journal.status, 'COMPLETE')

    def test_precheck_is_not_classified_as_read_only(self):
        self.assertIn('precheck_out', WRITE_ACTIONS)
        self.assertIn('precheck_out', APDU_ACTIONS)

    def test_real_identities_and_real_backends_are_not_accepted(self):
        with self.assertRaises(ValueError):
            Binding(source_card='8213')
        with self.assertRaises(TypeError):
            Coordinator(object(), approved())


if __name__ == '__main__':
    unittest.main()
