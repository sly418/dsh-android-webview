#!/usr/bin/env python3
"""Verify a built DshWebView APK: integrity, required resources, size and SHA-256.

Uses only the Python standard library. Usage:

    python tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk

Optionally pass --sha256 <expected> to assert the digest matches a known build.
Exit code 0 = all checks passed, 1 = a check failed.
"""
import argparse
import hashlib
import os
import sys
import zipfile

REQUIRED = [
    "AndroidManifest.xml",
    "classes.dex",
    "assets/runtime/dsh.zip",
    "assets/shellruntime.zip",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", help="path to the APK")
    ap.add_argument("--sha256", help="expected SHA-256 (hex), optional")
    ap.add_argument("--min-dsh-mb", type=float, default=100.0,
                    help="minimum expected size of assets/runtime/dsh.zip in MiB")
    args = ap.parse_args()

    path = args.apk
    if not os.path.isfile(path):
        print(f"[FAIL] APK not found: {path}")
        return 1

    ok = True
    size = os.path.getsize(path)
    print(f"[INFO] APK: {path}")
    print(f"[INFO] Size: {size:,} bytes ({size / 1024 / 1024:.1f} MiB)")

    # SHA-256
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    digest = h.hexdigest()
    print(f"[INFO] SHA-256: {digest}")
    if args.sha256:
        expected = args.sha256.strip().lower().replace(" ", "")
        if digest != expected:
            print(f"[FAIL] SHA-256 mismatch. expected={expected}")
            ok = False
        else:
            print("[ OK ] SHA-256 matches expected value.")

    # ZIP integrity + required resources
    try:
        with zipfile.ZipFile(path) as z:
            bad = z.testzip()
            if bad is not None:
                print(f"[FAIL] Corrupt ZIP entry: {bad}")
                ok = False
            else:
                print("[ OK ] ZIP integrity (all entries readable).")
            names = set(z.namelist())
            for req in REQUIRED:
                # Native libs / classes may be split; dsh.zip and manifest are fixed.
                if req in names:
                    info = z.getinfo(req)
                    print(f"[ OK ] {req} ({info.file_size:,} bytes)")
                    if req == "assets/runtime/dsh.zip":
                        if info.file_size < args.min_dsh_mb * 1024 * 1024:
                            print(f"[FAIL] dsh.zip smaller than {args.min_dsh_mb} MiB "
                                  f"(likely LFS pointer, not the real file).")
                            ok = False
                elif req == "classes.dex":
                    dex = [n for n in names if n.startswith("classes") and n.endswith(".dex")]
                    if dex:
                        print(f"[ OK ] DEX present: {', '.join(sorted(dex))}")
                    else:
                        print("[FAIL] No classes*.dex found.")
                        ok = False
                else:
                    print(f"[FAIL] Missing required resource: {req}")
                    ok = False
            # Check for LFS pointer text mistakenly committed instead of real ZIP.
            dsh = z.read("assets/runtime/dsh.zip")[:64] if "assets/runtime/dsh.zip" in names else b""
            if dsh.startswith(b"version https://git-lfs.github.com/spec/v1"):
                print("[FAIL] dsh.zip is a Git LFS pointer, not the real payload.")
                ok = False
    except zipfile.BadZipFile as e:
        print(f"[FAIL] Not a valid ZIP/APK: {e}")
        return 1

    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
