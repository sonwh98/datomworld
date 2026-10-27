#!/usr/bin/env python3
"""Pinned Autobahn runner. Smoke proves plumbing, never adapter conformance."""
import argparse
import hashlib
import html
import json
import os
import platform
import re
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
SMOKE = ['1.1.1', '1.2.1', '2.1', '7.1.1']


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def policy():
    pin, catalogue, exceptions = (read(HERE / f) for f in
                                  ['pin.json', 'cases.json', 'exceptions.json'])
    if digest(HERE / 'cases.json') != pin['cases_sha256']:
        raise ValueError('case list differs from pin')
    if digest(HERE / 'exceptions.json') != pin['exceptions_sha256']:
        raise ValueError('exception list differs from pin')
    rows = catalogue['cases']
    ids = [r['id'] for r in rows]
    if len(ids) != pin['case_count'] or len(set(ids)) != len(ids):
        raise ValueError('missing/duplicate catalogue cases')
    if [r['index'] for r in rows] != list(range(1, len(rows) + 1)):
        raise ValueError('upstream catalogue indexes changed')
    by_id = {r['id']: r for r in rows}
    seen = set()
    for e in exceptions['exceptions']:
        key = (e['case'], e['role'])
        if key in seen or e['role'] not in ('client', 'server'):
            raise ValueError('duplicate/invalid exception role')
        seen.add(key)
        if e['case'] not in by_id or by_id[e['case']]['class'] != 'exception-listed':
            raise ValueError('exception must name exact exception-listed case')
        if not e['reason'] or not e['proposal'] or not e['equals']:
            raise ValueError('exception requires rationale, proposal and assertions')
        if any(v == 'FAILED' for v in e['equals'].values()):
            raise ValueError('FAILED may not be excepted')
    for r in rows:
        if r['class'] not in ('must-pass', 'exception-listed', 'not-applicable') or not r['reason']:
            raise ValueError('untriaged case')
        if r['class'] == 'exception-listed':
            for role in ('client', 'server'):
                if (r['id'], role) not in seen:
                    raise ValueError('missing role-specific exception')
    return pin, rows, exceptions['exceptions']


def selected(rows, mode):
    return SMOKE if mode == 'smoke' else [r['id'] for r in rows if r['class'] != 'not-applicable']


def configuration(role, ids, port, agent):
    c = {'outdir': '/reports/raw', 'cases': ids,
         'exclude-cases': [], 'exclude-agent-cases': {}}
    if role == 'server':  # testee server, Autobahn fuzzing CLIENT
        c['servers'] = [{'url': 'ws://127.0.0.1:%d' % port, 'agent': agent}]
    else:
        c['url'] = 'ws://127.0.0.1:%d' % port
    return c


def evaluate(raw, role, agent, mode):
    pin, rows, exceptions = policy()
    wanted = selected(rows, mode)
    records, issues = {}, []
    for path in sorted(Path(raw).glob('*.json')):
        if path.name == 'index.json':
            continue
        try:
            r = read(path)
        except (ValueError, OSError) as e:
            issues.append('unreadable raw report: %s: %s' % (path.name, e))
            continue
        if not isinstance(r, dict) or 'id' not in r:
            issues.append('unrecognized raw report: ' + path.name)
            continue
        cid = r['id']
        if cid not in wanted or cid in records:
            issues.append('unexpected/duplicate case: ' + str(cid))
        records[cid] = r
    output = []
    for cid in wanted:
        r = records.get(cid)
        errors = []
        if r is None:
            errors.append('missing result')
        else:
            if r.get('agent') != agent:
                errors.append('agent mismatch')
            # isServer describes the fuzzer, NOT the testee.
            if r.get('isServer') is not (role == 'client'):
                errors.append('role mismatch')
            for flag in ('wasOpenHandshakeTimeout', 'wasCloseHandshakeTimeout',
                         'wasServerConnectionDropTimeout'):
                if r.get(flag) is not False:
                    errors.append('timeout or missing field: ' + flag)
            e = next((x for x in exceptions if x['case'] == cid and x['role'] == role), None)
            required = e['equals'] if e else {'behavior': 'OK', 'behaviorClose': 'OK'}
            for key, val in required.items():
                if r.get(key) != val:
                    errors.append('%s: expected %r, got %r' % (key, val, r.get(key)))
            if r.get('behavior') == 'FAILED' or r.get('behaviorClose') == 'FAILED':
                errors.append('FAILED is never waived')
        output.append({'case': cid, 'accepted': not errors, 'errors': errors,
                       'behavior': r.get('behavior') if r else None,
                       'behaviorClose': r.get('behaviorClose') if r else None})
    return {'schema': 1, 'mode': mode, 'testee_role': role, 'agent': agent,
            'suite_image': pin['image'], 'cases_sha256': pin['cases_sha256'],
            'exceptions_sha256': pin['exceptions_sha256'],
            'scope': 'STUB PLUMBING ONLY' if mode == 'smoke' else 'PROPOSED J6 GATE (owner approval required)',
            'conformance_claim': False, 'policy_status': 'proposal',
            'accepted': not issues and all(x['accepted'] for x in output),
            'issues': issues, 'expected': len(wanted), 'received': len(records), 'results': output}


def render(report, out):
    write(Path(out) / 'summary.json', report)
    esc = lambda x: html.escape(str(x))
    body = '\n'.join('<tr><td>%s</td><td>%s</td><td>%s</td></tr>' %
                     (esc(r['case']), esc(r['accepted']), esc('; '.join(r['errors'])))
                     for r in report['results'])
    Path(out, 'summary.html').write_text('<!doctype html><meta charset="utf-8">'
        '<title>J6 harness report</title><h1>%s</h1><p>Accepted: %s; expected: %s; received: %s</p>'
        '<pre>%s</pre><table><tr><th>Case</th><th>Accepted</th><th>Errors</th></tr>%s</table>' %
        (esc(report['scope']), esc(report['accepted']), report['expected'], report['received'],
         esc(report['issues']), body))


def wait_port(port, child, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if child.poll() is not None:
            raise RuntimeError('child exited before listening: %s' % child.returncode)
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.2):
                return
        except OSError:
            time.sleep(.1)
    raise TimeoutError('listener readiness timeout')


def stop(child):
    if child is not None and child.poll() is None:
        os.killpg(child.pid, signal.SIGTERM)
        try:
            child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=3)


def run(args):
    pin, rows, _ = policy()
    if not args.adapter or args.adapter[0] != '--':
        raise ValueError('supply -- followed by the testee command (no shell)')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.agent):
        raise ValueError('agent must be URL-safe: letters, numbers, underscore or hyphen')
    if not 1024 <= args.port <= 65535 or args.timeout <= 0:
        raise ValueError('invalid unprivileged port or timeout')
    # Refuse to attach to an unrelated listener left by another run.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', args.port))
    command = args.adapter[1:]
    if not command:
        raise ValueError('missing testee command')
    if args.mode != 'smoke' and any('stub.clj' in x for x in command):
        raise ValueError('stub cannot run the proposed J6 gate')
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)  # stale reports can never pass a rerun
    (out / 'raw').mkdir()
    ids = selected(rows, args.mode)
    write(out / 'spec.json', configuration(args.role, ids, args.port, args.agent))
    # FuzzingServer /runCase indexes the FILTERED specCases, not all Cases.
    (out / 'case-indexes.txt').write_text(','.join(str(i) for i in range(1, len(ids) + 1)))
    env = dict(os.environ, J6_ROLE=args.role, J6_PORT=str(args.port), J6_AGENT=args.agent,
               J6_URL='ws://127.0.0.1:%d' % args.port,
               J6_CASE_INDEXES=str(out / 'case-indexes.txt'), J6_MODE=args.mode,
               J6_FRAME_LIMIT='67108864', J6_MESSAGE_LIMIT='67108864')
    name = 'datom-j6-' + uuid.uuid4().hex[:12]
    # Upstream listenWS does not bind to the URL's loopback host. Publish
    # its server through Docker's loopback-only port mapping instead.
    network = (['--network', 'host'] if args.role == 'server' else
               ['-p', '127.0.0.1:%d:%d' % (args.port, args.port)])
    docker = ['docker', 'run', '--rm', '--name', name, '--platform', pin['platform']] + network + [
              '--user', '%d:%d' % (os.getuid(), os.getgid()),
              '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
              '-v', str(out) + ':/reports', '-v', str(out / 'spec.json') + ':/config/spec.json:ro',
              pin['image'], 'wstest', '-m', 'fuzzingclient' if args.role == 'server' else 'fuzzingserver',
              '-s', '/config/spec.json']
    processes, failures = [], []
    start = time.time()
    try:
        with open(out / 'testee.log', 'w') as testlog, open(out / 'wstest.log', 'w') as suite_log:
            def spawn(cmd, log):
                p = subprocess.Popen(cmd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                processes.append(p)
                return p
            if args.role == 'server':
                adapter = spawn(command, testlog)
                wait_port(args.port, adapter)
                suite = spawn(docker, suite_log)
                rc = suite.wait(timeout=args.timeout)
                if rc or adapter.poll() is not None:
                    failures.append('suite failed or server exited: %s/%s' % (rc, adapter.poll()))
            else:
                suite = spawn(docker, suite_log)
                wait_port(args.port, suite)
                adapter = spawn(command, testlog)
                rc = adapter.wait(timeout=args.timeout)
                if rc:
                    failures.append('client failed: %s' % rc)
                # Client must await updateReports response. Require report completion before shutdown.
                end = time.monotonic() + 15
                while not (out / 'raw/index.json').exists() and time.monotonic() < end:
                    time.sleep(.1)
                if not (out / 'raw/index.json').exists() or suite.poll() is not None:
                    failures.append('fuzzingserver/report completion failed')
    except (RuntimeError, OSError, subprocess.TimeoutExpired, TimeoutError) as e:
        failures.append(str(e))
    finally:
        for child in reversed(processes):
            stop(child)
        subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    result = evaluate(out / 'raw', args.role, args.agent, args.mode)
    result['issues'].extend(failures)
    result['accepted'] = result['accepted'] and not failures
    result['elapsed_seconds'] = round(time.time() - start, 3)
    result['command'] = command
    result['runner_platform'] = platform.platform()
    result['runner_machine'] = platform.machine()
    result['python_version'] = platform.python_version()
    result['declared_test_frame_limit'] = int(env['J6_FRAME_LIMIT'])
    result['declared_test_message_limit'] = int(env['J6_MESSAGE_LIMIT'])
    result['source_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    render(result, out)
    print(json.dumps({k: result[k] for k in ['scope', 'accepted', 'expected', 'received', 'issues']}))
    return 0 if result['accepted'] else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest='action', required=True)
    sub.add_parser('check')
    cat = sub.add_parser('verify-catalogue')
    cat.add_argument('exported')
    ev = sub.add_parser('evaluate')
    rn = sub.add_parser('run')
    for parser in (ev, rn):
        parser.add_argument('--role', choices=['client', 'server'], required=True, help='testee role')
        parser.add_argument('--agent', required=True)
        parser.add_argument('--mode', choices=['smoke', 'gate-proposal'], default='smoke')
        parser.add_argument('--out', required=True)
    ev.add_argument('--raw', required=True)
    rn.add_argument('--port', type=int, default=19001)
    rn.add_argument('--timeout', type=int, default=1800)
    rn.add_argument('adapter', nargs=argparse.REMAINDER)
    a = ap.parse_args()
    if a.action == 'verify-catalogue':
        pin, rows, _ = policy()
        exported = read(a.exported)
        keys = ['id', 'index', 'category', 'description', 'expectation']
        expected = [{k: r[k] for k in keys} for r in rows]
        if exported != {'version': pin['release'], 'cases': expected}:
            raise ValueError('exported upstream catalogue differs from pinned inventory')
        print('Verified all 517 upstream case records against pinned inventory')
        return 0
    if a.action == 'check':
        pin, rows, exceptions = policy()
        print(json.dumps({'image': pin['image'], 'cases': len(rows), 'exceptions': len(exceptions),
                          'required': len(selected(rows, 'gate-proposal'))}))
        return 0
    if a.action == 'run':
        return run(a)
    Path(a.out).mkdir(parents=True, exist_ok=False)
    r = evaluate(a.raw, a.role, a.agent, a.mode)
    render(r, a.out)
    return 0 if r['accepted'] else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError) as e:
        print('HARNESS ERROR: ' + str(e), file=sys.stderr)
        sys.exit(2)
