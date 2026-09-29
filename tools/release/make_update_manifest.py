#!/usr/bin/env python3
"""Write fryapp-update.json (contract C-6) for a signed release APK. Standard library only.

usage: make_update_manifest.py --apk fryapp-0.4.1.apk --tag app-v0.4.1 --channel stable \
         --version-code 8 --version-name 0.4.1 --min-supported 6 \
         --cert signing/release-cert.sha256 [--notes "..."] --out fryapp-update.json

The APK URL is always this repository's release asset for --tag; the manifest never points
anywhere else. The app refuses a manifest whose channel, package, URL, hash or size is wrong.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys

REPO_RELEASES = "https://github.com/Fry-Networks/fry-app-android/releases/download"
PACKAGE = "com.frynetworks.fryapp"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
TAG = re.compile(r"^app-v\d+\.\d+\.\d+(-(rc|test)\.\d+)?$")
ASSET = re.compile(r"^fryapp-[0-9A-Za-z.-]+\.apk$")
MAX_NOTES = 500


def sha256_and_size(path):
    digest = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def read_pin(value):
    """--cert is a path to a pin file or the hex digest itself; one or more digests, one per line."""
    text = open(value).read() if os.path.isfile(value) else value
    pins = [line.strip().lower() for line in text.splitlines() if line.strip()]
    if not pins or not all(HEX64.match(p) for p in pins):
        raise ValueError("certificate pin must be 64 lowercase hex characters per line")
    return pins


def build(apk, tag, channel, version_code, version_name, min_supported, pins, notes, now=None):
    if channel not in ("stable", "test"):
        raise ValueError("channel must be stable or test")
    if not TAG.match(tag):
        raise ValueError("tag must look like app-vX.Y.Z, app-vX.Y.Z-rc.N or app-vX.Y.Z-test.N")
    asset = os.path.basename(apk)
    if not ASSET.match(asset):
        raise ValueError("APK file name must be fryapp-<version>.apk")
    if version_code <= 0 or min_supported < 0 or min_supported > version_code:
        raise ValueError("need 0 <= minSupportedVersionCode <= versionCode and versionCode > 0")
    if len(notes) > MAX_NOTES:
        raise ValueError("notes longer than %d characters" % MAX_NOTES)
    sha, size = sha256_and_size(apk)
    now = now or datetime.datetime.now(datetime.timezone.utc)
    return {
        "schema": 1,
        "channel": channel,
        "package": PACKAGE,
        "versionCode": version_code,
        "versionName": version_name,
        "url": "%s/%s/%s" % (REPO_RELEASES, tag, asset),
        "sha256": sha,
        "size": size,
        "minSupportedVersionCode": min_supported,
        "certSha256": pins,
        "notes": notes,
        "publishedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--apk", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--channel", required=True, choices=["stable", "test"])
    p.add_argument("--version-code", required=True, type=int)
    p.add_argument("--version-name", required=True)
    p.add_argument("--min-supported", required=True, type=int)
    p.add_argument("--cert", required=True)
    p.add_argument("--notes", default="")
    p.add_argument("--out", required=True)
    a = p.parse_args(argv)
    try:
        manifest = build(a.apk, a.tag, a.channel, a.version_code, a.version_name, a.min_supported, read_pin(a.cert), a.notes)
    except (ValueError, OSError) as e:
        print("make_update_manifest: %s" % e, file=sys.stderr)
        return 1
    with open(a.out, "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("make_update_manifest: wrote %s (%s v%s code %d, %d bytes)" % (a.out, a.channel, a.version_name, a.version_code, manifest["size"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
