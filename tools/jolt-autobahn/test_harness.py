"""Acceptance-policy tests: corrupted/incomplete evidence must not turn green."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import harness as h


class ReportPolicy(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.raw = Path(self.temp.name)
        for cid in h.SMOKE:
            self.put(cid)

    def put(self, cid, **changes):
        record = dict(id=cid, agent='stub', isServer=False, behavior='OK', behaviorClose='OK',
                      wasOpenHandshakeTimeout=False, wasCloseHandshakeTimeout=False,
                      wasServerConnectionDropTimeout=False)
        record.update(changes)
        (self.raw / (cid + '.json')).write_text(json.dumps(record))

    def result(self):
        return h.evaluate(self.raw, 'server', 'stub', 'smoke')

    def test_smoke_never_claims_conformance(self):
        r = self.result()
        self.assertTrue(r['accepted'])
        self.assertFalse(r['conformance_claim'])
        h.render(r, self.raw)
        self.assertIn('STUB PLUMBING ONLY', (self.raw / 'summary.html').read_text())

    def test_missing_case(self):
        (self.raw / (h.SMOKE[0]+'.json')).unlink()
        self.assertFalse(self.result()['accepted'])

    def test_duplicate_case(self):
        (self.raw / 'duplicate.json').write_bytes((self.raw / (h.SMOKE[0]+'.json')).read_bytes())
        self.assertFalse(self.result()['accepted'])

    def test_unexpected_case(self):
        self.put('99.9')
        self.assertFalse(self.result()['accepted'])

    def test_bad_evidence(self):
        for change in [dict(agent='another'), dict(isServer=True), dict(behavior='FAILED'),
                       dict(behavior='NON-STRICT'), dict(behaviorClose='INFORMATIONAL'),
                       dict(wasOpenHandshakeTimeout=True), dict(wasCloseHandshakeTimeout=True),
                       dict(wasServerConnectionDropTimeout=None)]:
            with self.subTest(change=change):
                self.put(h.SMOKE[0], **change)
                self.assertFalse(self.result()['accepted'])

    def test_exception_requires_actual_protocol_error(self):
        for file in self.raw.glob('*.json'):
            file.unlink()
        with patch.object(h, 'selected', return_value=['7.13.1']):
            for code, behavior, expected in [(1002, 'INFORMATIONAL', True),
                                              (5000, 'INFORMATIONAL', False),
                                              (1002, 'FAILED', False)]:
                self.put('7.13.1', behavior=behavior, behaviorClose='INFORMATIONAL', remoteCloseCode=code)
                self.assertEqual(expected, self.result()['accepted'])

    def test_catalogue_and_exact_role_coverage(self):
        _, rows, exceptions = h.policy()
        self.assertEqual(517, len(rows))
        self.assertEqual(301, len(h.selected(rows, 'gate-proposal')))
        self.assertEqual(4, len(exceptions))


if __name__ == '__main__':
    unittest.main()
