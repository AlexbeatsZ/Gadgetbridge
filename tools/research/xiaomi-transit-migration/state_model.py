#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Offline migration coordinator model; no HTTP, ADB, Bluetooth or APDU transport.

This validates a proposed coordinator, not Xiaomi's server or card continuity.
All identities are synthetic. Backend reconciliation is a simulation oracle;
real reconciliation needs the actual server's documented/observed result.
"""
from dataclasses import asdict, dataclass, field
import argparse
import json
from pathlib import Path


@dataclass(frozen=True)
class Binding:
    account: str = 'TEST_ACCOUNT'
    source_se: str = 'TEST_K70_SE'
    target_se: str = 'TEST_S4_SE'
    product: str = 'TEST_TRANSIT_PRODUCT'
    source_card: str = 'TEST_CARD_ID'

    def __post_init__(self):
        if any(not isinstance(v, str) or not v.startswith('TEST_') for v in asdict(self).values()):
            raise ValueError('only synthetic TEST_ identities are accepted')
        if self.source_se == self.target_se:
            raise ValueError('source and target must be different simulated SEs')


@dataclass(frozen=True)
class Preconditions:
    account_authorized: bool = True
    source_owned: bool = True
    source_out_allowed: bool = True
    target_in_allowed: bool = True
    target_product_allowed: bool = True
    target_free: bool = True
    source_adapter_ready: bool = True
    target_adapter_ready: bool = True
    recovery_ready: bool = True
    exclusive_access: bool = True
    write_authorized: bool = False
    campus_preservation_confirmed: bool = False

    def missing(self):
        return sorted(k for k, v in asdict(self).items() if v is not True)


# PRECHECK is deliberately treated as a write: CHECK_SHIFT_OUT can return APDUs.
# Session allocation, script pulls and result uploads are not read-only probes.
STEPS = (
    ('query_preflight', False),
    ('precheck_out', True),
    ('prepare_out', True),
    ('create_out_session', True),
    ('start_out_operation', True),
    ('pull_out_scripts', True),
    ('write_out_scripts', True),
    ('report_out', True),
    ('confirm_out', True),
    ('query_cloud', False),
    ('start_in_order', True),
    ('create_in_session', True),
    ('start_in_operation', True),
    ('pull_in_scripts', True),
    ('write_in_scripts', True),
    ('report_in', True),
    ('query_target', False),
    ('query_campus', False),
)
WRITE_ACTIONS = frozenset(name for name, writing in STEPS if writing)
APDU_ACTIONS = frozenset({'precheck_out', 'write_out_scripts', 'write_in_scripts'})
PULL_ACTIONS = frozenset({'pull_out_scripts', 'pull_in_scripts'})
REPORT_ACTIONS = frozenset({'report_out', 'report_in'})


@dataclass
class Journal:
    binding: Binding = field(default_factory=Binding)
    position: int = 0
    status: str = 'READY'
    reason: str = ''
    sequence: int = 0
    inflight: dict | None = None
    receipts: list = field(default_factory=list)
    pending_pulls: int = 0
    script_batches: dict = field(default_factory=lambda: {'out': 0, 'in': 0})
    script_receipts: dict = field(default_factory=dict)

    def save(self, path):
        # Synthetic model journal only. Production needs encrypted atomic storage.
        path = Path(path)
        tmp = path.with_suffix(path.suffix + '.tmp')
        tmp.write_text(json.dumps(asdict(self), sort_keys=True))
        tmp.replace(path)

    @classmethod
    def load(cls, path):
        row = json.loads(Path(path).read_text())
        row['binding'] = Binding(**row['binding'])
        return cls(**row)


class AmbiguousResult(Exception):
    pass


class SimulationBackend:
    """An explicit fake. Never contacts Xiaomi and never transmits real card data."""

    def __init__(self, binding=None, batches=1):
        if type(batches) is not int or not 1 <= batches <= 10:
            raise ValueError('the offline fixture supports 1..10 synthetic batches')
        self.binding = binding or Binding()
        self.batches = batches
        self.accepted_batches = {'out': 0, 'in': 0}
        self.report_payloads = []
        self.calls = []
        self.applied = {}
        self.failures = {}
        self.uncertain = set()
        self.source_usable = True
        self.cloud_ready = False
        self.target_issued = False
        self.campus_valid = True
        self.owner_matches = True
        self.cloud_product_matches = True
        self.cloud_lineage_matches = True
        self.target_numbers_match = True
        self.se_balance_ok = True
        self.sw_ok = True
        self.payment_required = False

    def fault_once(self, action, mode):
        if mode not in ('before', 'after', 'pending'):
            raise ValueError('unknown synthetic fault')
        self.failures.setdefault(action, []).append(mode)

    def perform(self, action, intent, receipt=None):
        self.calls.append(action)
        if action in REPORT_ACTIONS:
            # A saved synthetic response is required even if this report times out.
            direction = action.removeprefix('report_')
            expected = self._script_receipt(direction)
            if receipt != expected:
                raise ValueError('report must use the original session/scriptsId/responses')
            self.report_payloads.append(dict(receipt))
        modes = self.failures.get(action, [])
        mode = modes.pop(0) if modes else None
        if mode == 'before':
            raise AmbiguousResult('synthetic transport lost before apply')
        if mode == 'pending':
            if action not in PULL_ACTIONS:
                raise ValueError('pending is only modeled for script pulls')
            return {'pending': True, 'mapped_local_code': 3007}
        result = {'ok': True}
        if action == 'query_preflight':
            result['ok'] = self.owner_matches
        elif action == 'prepare_out':
            result['ok'] = not self.payment_required
            result['payment_required'] = self.payment_required
        elif action == 'write_out_scripts':
            result['ok'] = self.sw_ok
            if self.sw_ok:
                self.source_usable = False
        elif action == 'confirm_out':
            self.cloud_ready = True
        elif action == 'query_cloud':
            result['ok'] = self.cloud_ready and self.cloud_product_matches and self.cloud_lineage_matches
        elif action == 'write_in_scripts':
            result['ok'] = self.sw_ok
            if self.sw_ok:
                self.target_issued = True
                self.cloud_ready = False
        elif action == 'query_target':
            result['ok'] = self.target_issued and self.target_numbers_match and self.se_balance_ok
        elif action == 'query_campus':
            result['ok'] = self.campus_valid
        elif action in REPORT_ACTIONS:
            self.accepted_batches[direction] += 1
            result['more_scripts'] = self.accepted_batches[direction] < self.batches
        if action in APDU_ACTIONS:
            result['responses_saved'] = True
            if action.startswith('write_'):
                result['script_receipt'] = self._script_receipt(action.split('_')[1])
        if intent:
            self.applied[intent] = dict(result)
        if mode == 'after':
            raise AmbiguousResult('synthetic transport lost after apply')
        return result

    def _script_receipt(self, direction):
        batch = self.accepted_batches[direction]
        return {'session_id': 'TEST_SESSION_' + direction,
                'scripts_id': f'TEST_SCRIPTS_{direction}_{batch}',
                'operation': 'TEST_SHIFT_' + direction.upper(),
                'action_result': self.sw_ok,
                'responses': [f'TEST_RAPDU_{direction}_{batch}']}

    def reconcile(self, action, intent):
        self.calls.append('query_reconcile:' + action)
        if intent in self.uncertain:
            return 'UNKNOWN', None
        if intent in self.applied:
            return 'APPLIED', self.applied[intent]
        return 'NOT_APPLIED', None


class Coordinator:
    def __init__(self, backend, preconditions, journal=None, journal_path=None):
        if type(backend) is not SimulationBackend:
            raise TypeError('this research model accepts only its offline fake backend')
        self.backend = backend
        self.preconditions = preconditions
        self.journal = journal or Journal(binding=backend.binding)
        self.journal_path = journal_path

    def _persist(self):
        if self.journal_path is not None:
            self.journal.save(self.journal_path)

    def _hold(self, reason, status='HOLD'):
        self.journal.status = status
        self.journal.reason = reason
        self._persist()

    def _check_binding(self):
        if self.backend.binding != self.journal.binding:
            self._hold('account, product or SE binding changed')
            return False
        return True

    def _accept(self, action, result):
        j = self.journal
        if result.get('pending'):
            j.inflight = None  # A definite DATA_NOT_READY reply; no script delivered.
            j.pending_pulls += 1
            if j.pending_pulls >= 3:  # Simulation bound, not a claimed Xiaomi constant.
                self._hold('script data still not ready; resume existing session later')
            self._persist()
            return
        j.receipts.append({'action': action, 'result': result})
        if action.startswith('write_'):
            direction = action.split('_')[1]
            key = f'{direction}:{j.script_batches[direction]}'
            # Persist the original response before the report can run. These
            # TEST_* placeholders are not operational APDU response material.
            j.script_receipts[key] = result['script_receipt']
        j.inflight = None
        if result.get('ok') is not True:
            # A definite failure is still a receipt. Preserve partial/failure
            # evidence before stopping; success and failure must not look alike.
            self._hold('failed verification or script status: ' + action)
            return
        if action in REPORT_ACTIONS and result.get('more_scripts'):
            direction = action.removeprefix('report_')
            j.script_batches[direction] += 1
            j.position = next(i for i, row in enumerate(STEPS)
                              if row[0] == f'pull_{direction}_scripts')
        else:
            j.position += 1
        j.pending_pulls = 0
        j.status = 'COMPLETE' if j.position == len(STEPS) else 'READY'
        j.reason = ''
        self._persist()

    def step(self):
        j = self.journal
        if j.status != 'READY' or not self._check_binding():
            return
        action, writing = STEPS[j.position]
        if action == 'query_preflight':
            missing = self.preconditions.missing()
            if missing:
                self._hold(', '.join(missing), 'BLOCKED')
                return
        if writing and self.preconditions.missing():
            self._hold('write preconditions no longer satisfied', 'BLOCKED')
            return
        if j.inflight:
            self._hold('unresolved previous intent', 'RECONCILE')
            return
        j.sequence += 1
        intent = f'TEST_INTENT_{j.sequence}' if writing else None
        if writing:
            j.inflight = {'action': action, 'intent': intent}
            # Durable intent precedes the side effect. A crash between perform()
            # and receipt persistence must not silently repeat the operation.
            self._persist()
        try:
            if action in REPORT_ACTIONS:
                direction = action.removeprefix('report_')
                key = f'{direction}:{j.script_batches[direction]}'
                receipt = j.script_receipts.get(key)
                if receipt is None:
                    self._hold('original APDU response missing; issuer repair required')
                    return
                result = self.backend.perform(action, intent, receipt)
            else:
                result = self.backend.perform(action, intent)
            self._accept(action, result)
        except AmbiguousResult:
            if writing:
                self._hold('transport outcome unknown; do not repeat mutation', 'RECONCILE')
            else:
                self._hold('read unavailable; retry only the read', 'READ_RETRY')

    def recover(self):
        j = self.journal
        if j.status not in ('RECONCILE', 'READ_RETRY') or not self._check_binding():
            return
        if j.status == 'READ_RETRY':
            j.status, j.reason = 'READY', ''
            self._persist()
            return
        item = j.inflight
        state, result = self.backend.reconcile(item['action'], item['intent'])
        if state == 'UNKNOWN':
            self._hold('no authoritative reconciliation; issuer repair required')
        elif state == 'NOT_APPLIED':
            # Only an authoritative oracle is accepted. An empty cloud list or a
            # timeout is NOT evidence that a production operation did not apply.
            j.inflight = None
            j.status, j.reason = 'READY', ''
            self._persist()
        elif item['action'] in APDU_ACTIONS:
            # In reality a response may have been lost before durable storage.
            # Do not invent RAPDUs or replay the command based on card status alone.
            self._hold('APDU may have applied; original response missing; issuer repair required')
        else:
            self._accept(item['action'], result)

    def run(self, maximum_steps=100):
        for _ in range(maximum_steps):
            if self.journal.status != 'READY':
                break
            self.step()
        return self.journal.status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=('unknown-campus', 'success', 'lost-confirm', 'lost-apdu', 'lost-report', 'multiple-batches'), default='unknown-campus')
    args = parser.parse_args()
    backend = SimulationBackend(batches=3 if args.scenario == 'multiple-batches' else 1)
    gates = Preconditions(write_authorized=True, campus_preservation_confirmed=args.scenario != 'unknown-campus')
    if args.scenario == 'lost-confirm':
        backend.fault_once('confirm_out', 'after')
    if args.scenario == 'lost-apdu':
        backend.fault_once('write_out_scripts', 'after')
    if args.scenario == 'lost-report':
        backend.fault_once('report_out', 'before')
    model = Coordinator(backend, gates)
    model.run()
    if model.journal.status == 'RECONCILE':
        model.recover()
        model.run()
    print(json.dumps({'simulation_only': True, 'scenario': args.scenario,
                      'status': model.journal.status, 'reason': model.journal.reason,
                      'actions': backend.calls, 'source_usable': backend.source_usable,
                      'target_issued': backend.target_issued}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
