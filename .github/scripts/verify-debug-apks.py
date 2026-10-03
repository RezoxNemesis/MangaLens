from pathlib import Path
import os
import subprocess
import zipfile

sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
signer = sdk / "build-tools" / "35.0.0" / "apksigner"
for abi in ("arm64-v8a", "x86_64"):
    apk = Path(f"app/build/outputs/apk/debug/app-{abi}-debug.apk")
    with zipfile.ZipFile(apk) as archive:
        corrupt = archive.testzip()
        if corrupt is not None:
            raise RuntimeError(f"Corrupt APK entry: {corrupt}")
        if "AndroidManifest.xml" not in archive.namelist():
            raise RuntimeError(f"Missing APK manifest: {apk}")
    subprocess.run([str(signer), "verify", "--verbose", str(apk)], check=True)
    print(f"Verified archive and signature: {apk} ({apk.stat().st_size} bytes)")
