#!/usr/bin/env python3
"""
Sign APK using apksigner (v1 + v2 signature schemes).
v2 signing required for HarmonyOS and modern Android.

Usage:
  python3 sign_apk.py [input_unsigned.apk] [output_signed.apk]
"""
import subprocess, sys, os

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APKSIGNER = os.environ.get("NOBRAIN_APKSIGNER_JAR")
KEYSTORE  = os.environ.get("NOBRAIN_KEYSTORE")
ALIAS     = os.environ.get("NOBRAIN_KEY_ALIAS", "nobrain-os")
PASSWORD  = os.environ.get("NOBRAIN_KEYSTORE_PASS")
EXPECTED_CERT = os.environ.get("NOBRAIN_EXPECTED_CERT_SHA256")


def sign_apk(input_path, output_path):
    if not APKSIGNER:
        raise RuntimeError("Set NOBRAIN_APKSIGNER_JAR to apksigner.jar")
    if not KEYSTORE:
        raise RuntimeError("Set NOBRAIN_KEYSTORE to a keystore outside the repository")
    if not PASSWORD:
        raise RuntimeError("Set NOBRAIN_KEYSTORE_PASS before signing release APKs")

    # Auto-zipalign if input is unaligned (zipalign must come before v2 signing)
    import tempfile, sys as _sys
    _aligned_tmp = None
    if not input_path.endswith("-aligned.apk"):
        _aligned_tmp = input_path.replace(".apk", "-aligned.apk")
        _sys.path.insert(0, os.path.dirname(__file__))
        import zipalign as _za
        _za.zipalign(input_path, _aligned_tmp)
        input_path = _aligned_tmp

    print(f"Signing {input_path} → {output_path} (v1+v2)...")
    result = subprocess.run([
        "java", "-jar", APKSIGNER,
        "sign",
        "--ks",               KEYSTORE,
        "--ks-pass",          f"pass:{PASSWORD}",
        "--key-pass",         f"pass:{PASSWORD}",
        "--ks-key-alias",     ALIAS,
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "false",
        "--out",              output_path,
        input_path,
    ], capture_output=True, text=True)

    if result.returncode != 0:
        print("STDERR:", result.stderr)
        raise RuntimeError(f"apksigner failed (exit {result.returncode})")

    # Verify
    verify = subprocess.run(
        ["java", "-jar", APKSIGNER, "verify", "--print-certs", output_path],
        capture_output=True, text=True
    )
    if "certificate DN" not in verify.stdout:
        raise RuntimeError(f"Verification failed:\n{verify.stdout}\n{verify.stderr}")
    if EXPECTED_CERT and f"certificate SHA-256 digest: {EXPECTED_CERT}" not in verify.stdout:
        raise RuntimeError("Signed APK does not use the expected release certificate")

    size_mb = os.path.getsize(output_path) // 1024 // 1024
    idsig = output_path + ".idsig"
    idsig_info = f" + {os.path.getsize(idsig)//1024}KB .idsig" if os.path.exists(idsig) else " (no .idsig!)"
    print(f"Done! {output_path} ({size_mb}MB){idsig_info}")
    print(verify.stdout.strip())


if __name__ == '__main__':
    inp = sys.argv[1] if len(sys.argv) > 1 else \
        os.path.join(PROJECT_ROOT, 'android/app/build/outputs/apk/release/app-release-unsigned.apk')
    out = sys.argv[2] if len(sys.argv) > 2 else \
        os.path.join(PROJECT_ROOT, 'nobrain-linux-signed.apk')
    sign_apk(inp, out)
