#!/usr/bin/env python3
"""Bound CI stages and retain diagnostics without printing env or argv."""
import os
import pathlib
import signal
import subprocess
import sys
import threading

seconds, output, *command = sys.argv[1:]
path = pathlib.Path(output)
path.parent.mkdir(parents=True, exist_ok=True)
with path.open('w') as log:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True, text=True, bufsize=1)

    def forward():
        for line in process.stdout:
            log.write(line); log.flush()
            print(line, end='', flush=True)

    reader = threading.Thread(target=forward, daemon=True)
    reader.start()
    try:
        code = process.wait(timeout=int(seconds))
    except subprocess.TimeoutExpired:
        print(f'Native CI stage exceeded {seconds}s; collecting process names and stacks', flush=True)
        diagnostic = path.with_suffix('.diagnostics.log')
        with diagnostic.open('w') as trace:
            subprocess.run(['ps', '-axo', 'pid,ppid,stat,etime,comm'], stdout=trace, stderr=trace, timeout=10)
            # Inspect only this stage's process tree. No env, full argv, or keys.
            rows = subprocess.check_output(['ps', '-axo', 'pid=,ppid='], text=True)
            parents = {int(a): int(b) for a, b in (line.split() for line in rows.splitlines())}
            descendants = {process.pid}
            while True:
                found = {pid for pid, parent in parents.items() if parent in descendants}
                if found <= descendants: break
                descendants |= found
            for pid in sorted(descendants)[:12]:
                subprocess.run(['sample', str(pid), '1', '1', '-file', str(path.parent / f'stack-{pid}.log')],
                               stdout=trace, stderr=trace, timeout=10)
        os.killpg(process.pid, signal.SIGTERM)
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL); process.wait()
        code = 124
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL); process.wait()
        reader.join(timeout=5)
sys.exit(code)
