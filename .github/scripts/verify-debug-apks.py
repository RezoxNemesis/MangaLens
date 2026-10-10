from pathlib import Path
import argparse
import hashlib
import os
import re
import runpy
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--variant", choices=("debug", "selfTest"), default="debug")
args = parser.parse_args()
sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
signer = sdk / "build-tools" / "35.0.0" / "apksigner"
expected_package = "com.mangalens.selftest" if args.variant == "selfTest" else "com.mangalens"
metadata_verifier = runpy.run_path(str(Path(__file__).resolve().parents[2] / "scripts/verify-bundled-model-metadata.py"))["verify_archive"]
dex_by_abi = {}
for abi in ("arm64-v8a", "x86_64"):
    apk = Path(f"app/build/outputs/apk/{args.variant}/app-{abi}-{args.variant}.apk")
    with zipfile.ZipFile(apk) as archive:
        corrupt = archive.testzip()
        if corrupt is not None:
            raise RuntimeError(f"Corrupt APK entry: {corrupt}")
        metadata_verifier(archive)
        names = set(archive.namelist())
        dex_names = sorted(name for name in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name))
        if not dex_names or "classes.dex" not in dex_names:
            raise RuntimeError(f"Missing application DEX: {apk}")
        dex_by_abi[abi] = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in dex_names}
        for library in ("libmangalens_whisper.so", "libmangalens_orez_native.so", "libpython.so", "libonnxruntime.so", "libonnxruntime4j_jni.so"):
            if f"lib/{abi}/{library}" not in names:
                raise RuntimeError(f"Missing required {abi} native runtime {library}: {apk}")
        if "AndroidManifest.xml" not in names:
            raise RuntimeError(f"Missing APK manifest: {apk}")
        for notice in ("material-icons-LICENSE.txt", "material-icons-NOTICE.txt", "onnxruntime-LICENSE.txt", "onnxruntime-NOTICE.txt", "onnxruntime-ThirdPartyNotices.txt"):
            if f"assets/third_party/{notice}" not in names:
                raise RuntimeError(f"Missing bundled license/attribution {notice}: {apk}")
        for asset in ("vocab.txt", "MODEL-CARD.md", "model_config.json", "tokenizer_config.json", "pooling_config.json", "public-metadata-receipt.json"):
            if f"assets/semantic/{asset}" not in names:
                raise RuntimeError(f"Missing pinned semantic metadata/tokenizer {asset}: {apk}")
        if hashlib.sha256(archive.read("assets/semantic/vocab.txt")).hexdigest() != "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3":
            raise RuntimeError(f"The packaged semantic vocabulary does not match its pin: {apk}")
        for notice, expected_hash in (
            ("onnxruntime-LICENSE.txt", "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
            ("onnxruntime-ThirdPartyNotices.txt", "cf7342f7ba482ef715ae58f5f497a8d3564fa255164175aea324cd293c5701a0"),
        ):
            if hashlib.sha256(archive.read(f"assets/third_party/{notice}")).hexdigest() != expected_hash:
                raise RuntimeError(f"Runtime license/attribution differs from its upstream source {notice}: {apk}")
        if "assets/orez/licenses/Qwen-GGUF-Apache-2.0.txt" not in names:
            raise RuntimeError(f"Missing bundled semantic publisher Apache 2.0 license: {apk}")
        if any(name.startswith("assets/semantic/") and name.lower().endswith((".onnx", ".onnx.part", ".gguf")) for name in names):
            raise RuntimeError(f"Optional semantic weights were bundled into the default APK: {apk}")
    subprocess.run([str(signer), "verify", "--verbose", str(apk)], check=True)
    badging = subprocess.check_output([str(sdk / "build-tools" / "35.0.0" / "aapt"), "dump", "badging", str(apk)], text=True)
    if f"package: name='{expected_package}'" not in badging:
        raise RuntimeError(f"Wrong application identity for {args.variant}: {apk}")
    print(f"Verified archive and signature: {apk} ({apk.stat().st_size} bytes)")
if dex_by_abi["arm64-v8a"] != dex_by_abi["x86_64"]:
    raise RuntimeError(f"Application DEX differs between {args.variant} ABI splits; rebuild both from the same source")
print(f"Verified identical application DEX across {args.variant} ABI splits")
