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

    def test_malformed_report(self):
        (self.raw / 'broken.json').write_text('{')
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

    def test_7_1_6_close_race_assertions(self):
        for file in self.raw.glob('*.json'):
            file.unlink()
        ok = dict(behavior='INFORMATIONAL', behaviorClose='INFORMATIONAL', remoteCloseCode=1000,
                  rxFrameStats={'1': 1, '8': 1}, createStats=True)
        cases = [({}, True),
                 (dict(rxFrameStats={'8': 1}), True),
                 (dict(rxFrameStats={'1': 1, '8': 1, '10': 1}), False),  # Pong after Close
                 (dict(remoteCloseCode=1002), False),
                 (dict(remoteCloseCode=None), False),
                 (dict(behavior='OK'), False),
                 (dict(behavior='FAILED'), False),
                 (dict(rxFrameStats=None), False),
                 (dict(createStats=False), False),
                 (dict(createStats=False, rxFrameStats={}), False),
                 (dict(createStats=None), False),
                 (dict(createStats=1), False),
                 (dict(createStats='true'), False),
                 (dict(rxFrameStats={'10': None}), False),
                 (dict(rxFrameStats={'10': False}), False),
                 (dict(rxFrameStats={'10': -1}), False),
                 (dict(rxFrameStats={'10': '1'}), False),
                 (dict(rxFrameStats={'1': None, '8': 1}), False),
                 (dict(rxFrameStats={'10': 0, '8': 1}), True),
                 (dict(wasCloseHandshakeTimeout=True), False)]
        with patch.object(h, 'selected', return_value=['7.1.6']):
            for role, is_server in (('client', True), ('server', False)):
                for change, expected in cases:
                    with self.subTest(role=role, change=change):
                        self.put('7.1.6', isServer=is_server, **dict(ok, **change))
                        self.assertEqual(expected, h.evaluate(self.raw, role, 'stub', 'smoke')['accepted'])
                self.put('7.1.6', isServer=is_server, behavior='INFORMATIONAL', behaviorClose='INFORMATIONAL',
                         remoteCloseCode=1000)  # no rxFrameStats at all
                self.assertFalse(h.evaluate(self.raw, role, 'stub', 'smoke')['accepted'])
                self.put('7.1.6', isServer=is_server, behavior='INFORMATIONAL', behaviorClose='INFORMATIONAL',
                         remoteCloseCode=1000, rxFrameStats={})  # createStats missing
                self.assertFalse(h.evaluate(self.raw, role, 'stub', 'smoke')['accepted'])

    def test_catalogue_and_exact_role_coverage(self):
        _, rows, exceptions = h.policy()
        self.assertEqual(517, len(rows))
        self.assertEqual(301, len(h.selected(rows, 'gate-proposal')))
        self.assertEqual(6, len(exceptions))
        classes = [r['class'] for r in rows]
        self.assertEqual((298, 3, 216), tuple(classes.count(c) for c in
                         ('must-pass', 'exception-listed', 'not-applicable')))
        self.assertEqual(['7.1.6', '7.13.1', '7.13.2'],
                         [r['id'] for r in rows if r['class'] == 'exception-listed'])
        self.assertEqual(1204, 4 * len(h.selected(rows, 'gate-proposal')))


if __name__ == '__main__':
    unittest.main()
