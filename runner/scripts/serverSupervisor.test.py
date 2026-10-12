import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPT = Path(__file__).with_name('serverSupervisor.py')
spec = importlib.util.spec_from_file_location('supervisor', SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SupervisorTest(unittest.TestCase):
    def launch(self, directory, command, parent=None, deadline=8):
        target = Path(directory) / 'owner.json'
        process = subprocess.Popen([sys.executable, str(SCRIPT), '--parent-pid', str(parent or os.getpid()),
                                    '--ownership', str(target), '--cwd', directory,
                                    '--deadline-seconds', str(deadline), '--', *command])
        until = time.monotonic() + 3
        while not target.exists() and process.poll() is None and time.monotonic() < until:
            time.sleep(0.02)
        return process, target

    def test_success(self):
        with tempfile.TemporaryDirectory() as directory:
            process, target = self.launch(directory, [sys.executable, '-c', 'pass'])
            self.assertEqual(process.wait(timeout=8), 0)
            self.assertEqual(json.loads(target.read_text())['state'], 'CLEANED')

    def test_killed_runner_descendant_group_is_cleaned(self):
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, '-c',
                       'import subprocess,time; subprocess.Popen(["sleep","30"]); time.sleep(30)']
            process, target = self.launch(directory, command)
            time.sleep(0.15)
            record = json.loads(target.read_text())
            os.kill(record['pgid'], signal.SIGKILL)
            self.assertNotEqual(process.wait(timeout=9), 0)
            self.assertFalse(module.members(record['pgid']))
            self.assertEqual(json.loads(target.read_text())['state'], 'CLEANED')

    def test_parent_loss_terminates_node_and_native_child_only(self):
        node = shutil.which('node')
        self.assertIsNotNone(node, 'Node is required by the server runner')
        with tempfile.TemporaryDirectory() as directory:
            parent = subprocess.Popen(['sleep', '30'])
            unrelated = subprocess.Popen(['sleep', '30'], start_new_session=True)
            observed = Path(directory) / 'descendants.json'
            script = (
                "const child=require('node:child_process').spawn('sleep',['30']);"
                "require('node:fs').writeFileSync(process.argv[1],"
                "JSON.stringify({node:process.pid,native:child.pid}));"
                "setInterval(()=>{},1000);"
            )
            process, target = self.launch(
                directory, [node, '-e', script, str(observed)], parent=parent.pid)
            try:
                until = time.monotonic() + 3
                while not observed.exists() and time.monotonic() < until:
                    time.sleep(0.02)
                self.assertTrue(observed.exists(), 'Node must spawn its native child before parent loss')
                descendants = json.loads(observed.read_text())
                before = module.processes()
                group = json.loads(target.read_text())['pgid']
                self.assertEqual(before[descendants['node']]['pgid'], group)
                self.assertEqual(before[descendants['native']]['pgid'], group)
                parent.kill()
                parent.wait()
                self.assertNotEqual(process.wait(timeout=9), 0)
                after = module.processes()
                self.assertNotIn(descendants['node'], after)
                self.assertNotIn(descendants['native'], after)
                self.assertFalse(module.members(group, after))
                self.assertEqual(json.loads(target.read_text())['state'], 'CLEANED')
                self.assertIsNone(unrelated.poll(), 'Unrelated process must survive cleanup')
            finally:
                if parent.poll() is None:
                    parent.kill()
                    parent.wait()
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=9)
                unrelated.kill()
                unrelated.wait()

    def test_deadline_returns_124_after_owned_group_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            process, target = self.launch(directory, [sys.executable, '-c', 'import time; time.sleep(30)'],
                                          deadline=0.2)
            self.assertEqual(process.wait(timeout=8), 124)
            record = json.loads(target.read_text())
            self.assertEqual(record['state'], 'CLEANED')
            self.assertFalse(module.members(record['pgid']))

    def test_cleanup_refuses_identity_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            child = subprocess.Popen(['sleep', '30'], start_new_session=True)
            target = Path(directory) / 'owner.json'
            target.write_text(json.dumps({'pgid': child.pid, 'members': {str(child.pid): 'wrong'}}))
            try:
                self.assertEqual(module.cleanup(target), 2)
                self.assertIsNone(child.poll())
            finally:
                child.kill()
                child.wait()


if __name__ == '__main__':
    unittest.main()
