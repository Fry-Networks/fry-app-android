#!/usr/bin/env python3
"""python3 -m unittest tools/release/test_make_update_manifest.py (stdlib only)."""
import datetime
import hashlib
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_update_manifest as m  # noqa: E402

PIN = "3fbf44760c28c17c2ce81f16cc875c28b820cb4989da819aeef862c18a867dbc"


class MakeUpdateManifestTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.apk = os.path.join(self.dir.name, "fryapp-0.4.1-rc.1.apk")
        self.bytes = b"PK\x03\x04" + bytes(range(256)) * 40
        with open(self.apk, "wb") as f:
            f.write(self.bytes)

    def tearDown(self):
        self.dir.cleanup()

    def build(self, **kw):
        args = dict(apk=self.apk, tag="app-v0.4.1-rc.1", channel="test", version_code=7, version_name="0.4.1-rc.1",
                    min_supported=6, pins=[PIN], notes="Fixes", now=datetime.datetime(2026, 9, 28, 20, 0, tzinfo=datetime.timezone.utc))
        args.update(kw)
        return m.build(**args)

    def test_c6_fields(self):
        out = self.build()
        self.assertEqual(out["schema"], 1)
        self.assertEqual(out["package"], "com.frynetworks.fryapp")
        self.assertEqual(out["url"], "https://github.com/Fry-Networks/fry-app-android/releases/download/app-v0.4.1-rc.1/fryapp-0.4.1-rc.1.apk")
        self.assertEqual(out["sha256"], hashlib.sha256(self.bytes).hexdigest())
        self.assertEqual(out["size"], len(self.bytes))
        self.assertEqual(out["certSha256"], [PIN])
        self.assertEqual(out["publishedAt"], "2026-09-28T20:00:00Z")
        self.assertEqual(sorted(out), sorted(["schema", "channel", "package", "versionCode", "versionName", "url", "sha256", "size",
                                              "minSupportedVersionCode", "certSha256", "notes", "publishedAt"]))

    def test_rejects_bad_input(self):
        for kw in (dict(channel="beta"), dict(tag="v0.4.1"), dict(tag="app-v0.4"), dict(notes="x" * 501),
                   dict(version_code=0), dict(min_supported=8)):
            with self.assertRaises(ValueError, msg=str(kw)):
                self.build(**kw)
        bad = os.path.join(self.dir.name, "app-release.apk")
        os.rename(self.apk, bad)
        with self.assertRaises(ValueError):
            self.build(apk=bad)

    def test_pin_from_file_or_value(self):
        pin_file = os.path.join(self.dir.name, "release-cert.sha256")
        with open(pin_file, "w") as f:
            f.write(PIN.upper() + "\n")
        self.assertEqual(m.read_pin(pin_file), [PIN])
        self.assertEqual(m.read_pin(PIN), [PIN])
        with self.assertRaises(ValueError):
            m.read_pin("abc")

    def test_cli_writes_json(self):
        out = os.path.join(self.dir.name, "fryapp-update.json")
        rc = m.main(["--apk", self.apk, "--tag", "app-v0.4.1-rc.1", "--channel", "test", "--version-code", "7",
                     "--version-name", "0.4.1-rc.1", "--min-supported", "6", "--cert", PIN, "--out", out])
        self.assertEqual(rc, 0)
        with open(out) as f:
            self.assertEqual(json.load(f)["versionCode"], 7)
        self.assertEqual(m.main(["--apk", self.apk, "--tag", "bad", "--channel", "test", "--version-code", "7",
                                 "--version-name", "x", "--min-supported", "6", "--cert", PIN, "--out", out]), 1)


if __name__ == "__main__":
    unittest.main()
