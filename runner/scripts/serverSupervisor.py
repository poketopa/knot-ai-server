#!/usr/bin/env python3
"""Own a job process group independently of the JVM and the Node runner."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def processes():
    raw = subprocess.check_output(['ps', '-axo', 'pid=,pgid=,stat=,lstart='], text=True)
    result = {}
    for line in raw.splitlines():
        fields = line.split(None, 3)
        if len(fields) == 4 and 'Z' not in fields[2]:
            result[int(fields[0])] = {'pgid': int(fields[1]), 'started': fields[3]}
    return result


def save(target, record):
    target = Path(target)
    temporary = target.with_suffix('.tmp')
    with open(temporary, 'w', encoding='utf8') as stream:
        os.chmod(temporary, 0o600)
        json.dump(record, stream)
    os.replace(temporary, target)


def members(pgid, snapshot=None):
    return {str(pid): item['started'] for pid, item in (snapshot or processes()).items()
            if item['pgid'] == pgid}


def terminate(pgid):
    for sig, deadline in [(signal.SIGTERM, 2), (signal.SIGKILL, 3)]:
        if not members(pgid):
            return True
        try:
            os.killpg(pgid, sig)
        except ProcessLookupError:
            return True
        until = time.monotonic() + deadline
        while time.monotonic() < until:
            if not members(pgid):
                return True
            time.sleep(0.05)
    return not members(pgid)


def cleanup(target):
    record = json.loads(Path(target).read_text())
    pgid = record['pgid']
    if not isinstance(pgid, int) or pgid <= 1 or pgid == os.getpgrp():
        return 2
    current = members(pgid)
    if not current:
        record['state'] = 'CLEANED'
        save(target, record)
        return 0
    # Fail closed: a reused group or an unrecorded process is never killed.
    if any(record.get('members', {}).get(pid) != start for pid, start in current.items()):
        return 2
    if not terminate(pgid):
        return 2
    record['state'] = 'CLEANED'
    save(target, record)
    return 0


def supervise(args):
    parent = processes().get(args.parent_pid)
    if not parent or args.deadline_seconds <= 0 or not args.command:
        return 2
    target = Path(args.ownership)
    if target.exists() and cleanup(target) != 0:
        return 2
    target.parent.mkdir(parents=True, exist_ok=True)
    child = subprocess.Popen(args.command, cwd=args.cwd, start_new_session=True,
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL)
    record = {'schemaVersion': 1, 'pgid': child.pid, 'parentPid': args.parent_pid,
              'parentStarted': parent['started'], 'state': 'RUNNING', 'members': {}}
    interrupted = False

    def stop(_sig, _frame):
        nonlocal interrupted
        interrupted = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    deadline = time.monotonic() + args.deadline_seconds
    failed = False
    timed_out = False
    try:
        while True:
            snapshot = processes()
            record['members'].update(members(child.pid, snapshot))
            save(target, record)
            status = child.poll()
            if status is not None:
                break
            if interrupted or snapshot.get(args.parent_pid) != parent:
                failed = True
                break
            if time.monotonic() >= deadline:
                failed = True
                timed_out = True
                break
            time.sleep(0.05)
    finally:
        cleaned = terminate(child.pid)
        try:
            child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            cleaned = False
        # Reap group leader before determining final group emptiness.
        cleaned = cleaned or not members(child.pid)
        record['state'] = 'CLEANED' if cleaned else 'CLEANUP_FAILED'
        save(target, record)
    if timed_out and cleaned:
        return 124
    return 0 if not failed and cleaned and child.returncode == 0 else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cleanup')
    parser.add_argument('--parent-pid', type=int)
    parser.add_argument('--ownership')
    parser.add_argument('--cwd')
    parser.add_argument('--deadline-seconds', type=float, default=3600)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.cleanup:
        return cleanup(args.cleanup)
    if args.command[:1] == ['--']:
        args.command = args.command[1:]
    if not args.parent_pid or not args.ownership or not args.cwd:
        return 2
    return supervise(args)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        # Never echo paths, credentials, process arguments or recording content.
        sys.stderr.write('SUPERVISOR_FAILED\n')
        sys.exit(2)
