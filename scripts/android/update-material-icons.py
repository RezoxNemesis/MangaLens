#!/usr/bin/env python3
"""Vendor only referenced extended icons from the pinned AndroidX source archive."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

VERSION = "1.7.6"
SOURCE_URL = f"https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-extended-android/{VERSION}/material-icons-extended-android-{VERSION}-sources.jar"
SOURCE_SHA = "9b22840d9d5ec83fca54783df66640c0e2d76bb164687d654c23bcc6c02f3a96"
CORE_SHA = "48d182051236b1dfec829b33bf4d3fd06d03c2876d532208e3f7490159d65c46"
ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "third_party/material-icons/manifest.json"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source-jar", type=Path, required=True)
parser.add_argument("--core-aar", type=Path, required=True)
args = parser.parse_args()
data = args.source_jar.read_bytes()
if hashlib.sha256(data).hexdigest() != SOURCE_SHA:
    raise SystemExit("Pinned AndroidX source archive checksum mismatch")
core_data = args.core_aar.read_bytes()
if hashlib.sha256(core_data).hexdigest() != CORE_SHA:
    raise SystemExit("Matching AndroidX core archive checksum mismatch")
with zipfile.ZipFile(io.BytesIO(core_data)) as core:
    with zipfile.ZipFile(io.BytesIO(core.read("classes.jar"))) as classes:
        core_classes = set(classes.namelist())

used = set()
for source in (ROOT / "app/src").rglob("*.kt"):
    if "androidx/compose/material/icons/" in source.as_posix():
        continue
    text = source.read_text()
    for family, name in re.findall(r"Icons\.((?:AutoMirrored\.)?(?:Outlined|Rounded|Filled|Default|Sharp|TwoTone))\.([A-Z][A-Za-z0-9]+)", text):
        used.add(family.replace("Default", "Filled").lower().replace(".", "/") + "/" + name)
    for family, name in re.findall(r"import\s+androidx\.compose\.material\.icons\.((?:automirrored\.)?[a-z]+)\.([A-Z][A-Za-z0-9]+)", text):
        used.add(family.replace(".", "/") + "/" + name)

old = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {"vendored": []}
old_files = {entry["path"]: entry["sha256"] for entry in old["vendored"]}
for name in old_files:
    if not re.fullmatch(r"app/src/main/java/androidx/compose/material/icons/(?:automirrored/)?(?:outlined|filled|rounded|sharp|twotone)/[A-Z][A-Za-z0-9]+\.kt", name):
        raise SystemExit(f"Invalid owned icon path: {name}")
    if (ROOT / name).is_symlink():
        raise SystemExit(f"Refusing symlinked icon: {name}")
selected = []
provided = []
with zipfile.ZipFile(io.BytesIO(data)) as upstream:
    for symbol in sorted(used):
        relative = "androidx/compose/material/icons/" + symbol
        if relative + "Kt.class" in core_classes:
            provided.append(symbol)
            continue
        member = "commonMain/" + relative + ".kt"
        contents = upstream.read(member)
        if b"Apache License, Version 2.0" not in contents:
            raise SystemExit(f"Missing upstream license header: {member}")
        name = "app/src/main/java/" + relative + ".kt"
        path = ROOT / name
        if path.is_symlink():
            raise SystemExit(f"Refusing symlinked icon: {name}")
        digest = hashlib.sha256(contents).hexdigest()
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() not in {digest, old_files.get(name)}:
            raise SystemExit(f"Preserve locally edited icon before updating: {name}")
        selected.append({"symbol": symbol, "source": member, "path": name, "bytes": len(contents), "sha256": digest})
    # Validate every owned path before making any changes.
    retained = {entry["path"] for entry in selected}
    for name, digest in old_files.items():
        path = ROOT / name
        if name not in retained and path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise SystemExit(f"Preserve locally edited unused icon before updating: {name}")
    for entry in selected:
        path = ROOT / entry["path"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(upstream.read(entry["source"]))
    for name in old_files.keys() - retained:
        (ROOT / name).unlink(missing_ok=True)

MANIFEST.parent.mkdir(parents=True, exist_ok=True)
MANIFEST.write_text(json.dumps({"upstream_version": VERSION, "source_url": SOURCE_URL,
    "source_sha256": SOURCE_SHA, "core_aar_sha256": CORE_SHA, "required_symbols": sorted(used), "provided_by_core": provided,
    "vendored": selected}, indent=2) + "\n")
print(f"Preserved {len(used)} referenced icon symbols: {len(provided)} from core, {len(selected)} exact upstream sources.")
