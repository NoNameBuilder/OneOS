#!/usr/bin/env python3
"""Host-side regression checks; no phone, mount or root operations.

Optionally pass an unmodified apktool-decoded SystemUI directory to check
exact patch application, idempotence and atomic refusal on class drift.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / 'switcher/assets/fold8_coloros.sh'
BUNDLE = ROOT / 'switcher/patches/fold8-coloros'
spec = importlib.util.spec_from_file_location('ui_patch', ROOT / 'switcher/patches/fold8_coloros_systemui.py')
patcher = importlib.util.module_from_spec(spec); spec.loader.exec_module(patcher)


class Fold8Tests(unittest.TestCase):
    def test_other_profiles_and_images_do_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage = Path(tmp)
            for profile, image, fileset in [('f8u', 'coloros17-findn6', 'n6-f8u'), ('f8', 'pixel11fold-yogi', 'pixel-f8u'), ('f8', 'coloros17-findn6', 'n6-f8u')]:
                marker = json.dumps(dict(image=image, set=fileset, created=0), indent=2) + '\n'
                (stage / '.oneos_stage').write_text(marker)
                for mode in ['prepare']:
                    env = dict(os.environ, STAGE=tmp, ONEOS_PROFILE=profile, BB='/does/not/exist')
                    r = subprocess.run(['/bin/sh', str(SCRIPT), mode], env=env, capture_output=True)
                    self.assertEqual(0, r.returncode, r.stderr)
                self.assertEqual({'.oneos_stage'}, {p.name for p in stage.iterdir()})

    def test_selected_profile_refuses_unexpected_systemui_before_mounts(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage = Path(tmp)
            (stage / '.oneos_stage').write_text(json.dumps(dict(image='coloros17-findn6', set='n6-f8', created=0), indent=2) + '\n')
            env = dict(os.environ, STAGE=tmp, ONEOS_PROFILE='f8', BB='/does/not/exist')
            r = subprocess.run(['/bin/sh', str(SCRIPT), 'prepare'], env=env, capture_output=True)
            self.assertNotEqual(0, r.returncode)
            self.assertIn(b'unexpected SystemUI path', r.stderr)
            self.assertFalse((stage / 'data').exists())

    def test_google_is_no_longer_fold8_only(self):
        # Play services and the Play Store: guest_google.sh, every ColorOS set.
        self.assertNotIn('snapshot)', SCRIPT.read_text())
        self.assertNotIn('GmsCore', SCRIPT.read_text())

    def test_exact_patch_and_atomic_refusal(self):
        if not ORIGINAL:
            self.skipTest('pass a pristine apktool SystemUI directory for the image regression')
        pins = json.loads((BUNDLE / 'classes.json').read_text())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            originals = {}
            for name, pin in pins.items():
                if pin['before']:
                    src = ORIGINAL / name
                    data = src.read_bytes()
                    self.assertEqual(pin['before'], hashlib.sha256(data).hexdigest(), name)
                    target = root / name; target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data); originals[name] = data
            drift = list(originals)[-1]
            (root / drift).write_bytes(originals[drift] + b'\n# drift\n')
            with self.assertRaises(ValueError):
                patcher.patch(root)
            for name, data in originals.items():
                self.assertEqual(data + (b'\n# drift\n' if name == drift else b''), (root / name).read_bytes())
            for name, data in originals.items():
                (root / name).write_bytes(data)
            patcher.patch(root)
            for name, pin in pins.items():
                self.assertEqual(pin['after'], hashlib.sha256((root / name).read_bytes()).hexdigest(), name)
            patcher.patch(root)  # idempotent


if __name__ == '__main__':
    ORIGINAL = Path(sys.argv.pop(1)) if len(sys.argv) > 1 else None
    unittest.main()
