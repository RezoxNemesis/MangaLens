from pathlib import Path
import argparse
import os
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--variant", choices=("debug", "selfTest"), default="debug")
args = parser.parse_args()
sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
signer = sdk / "build-tools" / "35.0.0" / "apksigner"
expected_package = "com.mangalens.selftest" if args.variant == "selfTest" else "com.mangalens"
for abi in ("arm64-v8a", "x86_64"):
    apk = Path(f"app/build/outputs/apk/{args.variant}/app-{abi}-{args.variant}.apk")
    with zipfile.ZipFile(apk) as archive:
        corrupt = archive.testzip()
        if corrupt is not None:
            raise RuntimeError(f"Corrupt APK entry: {corrupt}")
        names = set(archive.namelist())
        for library in ("libmangalens_whisper.so", "libmangalens_orez_native.so", "libpython.so"):
            if f"lib/{abi}/{library}" not in names:
                raise RuntimeError(f"Missing required {abi} native runtime {library}: {apk}")
        if "AndroidManifest.xml" not in names:
            raise RuntimeError(f"Missing APK manifest: {apk}")
        for notice in ("material-icons-LICENSE.txt", "material-icons-NOTICE.txt"):
            if f"assets/third_party/{notice}" not in names:
                raise RuntimeError(f"Missing bundled icon license/attribution {notice}: {apk}")
    subprocess.run([str(signer), "verify", "--verbose", str(apk)], check=True)
    badging = subprocess.check_output([str(sdk / "build-tools" / "35.0.0" / "aapt"), "dump", "badging", str(apk)], text=True)
    if f"package: name='{expected_package}'" not in badging:
        raise RuntimeError(f"Wrong application identity for {args.variant}: {apk}")
    print(f"Verified archive and signature: {apk} ({apk.stat().st_size} bytes)")
