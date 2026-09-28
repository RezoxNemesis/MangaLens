#!/usr/bin/env python3
from __future__ import annotations

import gzip
import io
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw"

OASST_URL = "https://huggingface.co/datasets/OpenAssistant/oasst1/resolve/main/2023-04-12_oasst_ready.messages.jsonl.gz"
DOLLY_URL = "https://huggingface.co/datasets/databricks/databricks-dolly-15k/resolve/main/databricks-dolly-15k.jsonl"
MULTIWOZ_URL = "https://raw.githubusercontent.com/budzianowski/multiwoz/master/data/MultiWOZ_2.0.zip"

def download(url: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "MangaLens-OREZ-Phase1/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r, tmp.open("wb") as out:
        shutil.copyfileobj(r, out, length=1024 * 1024)
    tmp.replace(path)

def write_record(out, prompt: str, response: str, source: str, record_id: str, domain: str = "general", turns=None) -> int:
    prompt, response = str(prompt).strip(), str(response).strip()
    if not prompt or not response:
        return 0
    rec = {
        "type": "conversation",
        "key": f"{source}:{record_id}",
        "domain": domain or "general",
        "language": "en",
        "turns": turns or [
            {"speaker": "user", "text": prompt},
            {"speaker": "assistant", "text": response},
        ],
        "prompt": prompt,
        "response": response,
        "source_record": record_id,
    }
    out.write(json.dumps(rec, ensure_ascii=False, separators=(",", ":")) + "\n")
    return 1

def build_oasst() -> int:
    source_dir = RAW / "openassistant-oasst1"
    source_dir.mkdir(parents=True, exist_ok=True)
    gz = source_dir / "oasst_ready.messages.jsonl.gz"
    download(OASST_URL, gz)

    messages = {}
    with gzip.open(gz, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.strip():
                try:
                    x = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if x.get("message_id") and x.get("text"):
                    messages[x["message_id"]] = x

    out_path = source_dir / "normalized.jsonl"
    count = 0
    with out_path.open("w", encoding="utf-8") as out:
        for mid, child in messages.items():
            parent = messages.get(child.get("parent_id"))
            if not parent:
                continue
            if parent.get("role") == "prompter" and child.get("role") == "assistant":
                count += write_record(
                    out, parent["text"], child["text"], "openassistant-oasst1", mid,
                    "conversation", [
                        {"speaker": "user", "text": parent["text"].strip()},
                        {"speaker": "assistant", "text": child["text"].strip()},
                    ]
                )
    return count

def build_dolly() -> int:
    source_dir = RAW / "databricks-dolly-15k"
    source_dir.mkdir(parents=True, exist_ok=True)
    raw = source_dir / "databricks-dolly-15k.jsonl"
    download(DOLLY_URL, raw)

    out_path = source_dir / "normalized.jsonl"
    count = 0
    with raw.open("r", encoding="utf-8", errors="replace") as f, out_path.open("w", encoding="utf-8") as out:
        for idx, line in enumerate(f, 1):
            try:
                x = json.loads(line)
            except json.JSONDecodeError:
                continue
            instruction = str(x.get("instruction", "")).strip()
            response = str(x.get("response", "")).strip()
            context = str(x.get("context", "")).strip()
            prompt = instruction if not context else instruction + "\n\nContext:\n" + context
            count += write_record(out, prompt, response, "databricks-dolly-15k", str(idx), str(x.get("category", "general")))
    return count

def build_multiwoz() -> int:
    source_dir = RAW / "multiwoz-2.2"
    source_dir.mkdir(parents=True, exist_ok=True)
    archive = source_dir / "MultiWOZ_2.0.zip"
    download(MULTIWOZ_URL, archive)

    data_path = None
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if name.endswith("/data.json") or name == "data.json":
                data_path = name
                break
        if not data_path:
            raise RuntimeError("MultiWOZ archive did not contain data.json")
        data = json.loads(z.read(data_path).decode("utf-8"))

    out_path = source_dir / "normalized.jsonl"
    count = 0
    with out_path.open("w", encoding="utf-8") as out:
        for dialogue_id, dialogue in data.items():
            log = dialogue.get("log", [])
            for i in range(len(log) - 1):
                a, b = log[i], log[i + 1]
                a_text, b_text = str(a.get("text", "")).strip(), str(b.get("text", "")).strip()
                if not a_text or not b_text:
                    continue
                count += write_record(
                    out, a_text, b_text, "multiwoz-2.2", f"{dialogue_id}:{i}",
                    "multi-domain-dialogue",
                    [
                        {"speaker": "user", "text": a_text},
                        {"speaker": "assistant", "text": b_text},
                    ],
                )
    return count

def main() -> None:
    counts = {
        "openassistant-oasst1": build_oasst(),
        "databricks-dolly-15k": build_dolly(),
        "multiwoz-2.2": build_multiwoz(),
    }
    manifest = {
        "phase": "1",
        "purpose": "licensed conversation source acquisition and normalization",
        "sources": counts,
        "raw_root": "data/raw",
        "status": "acquired-and-normalized",
    }
    out = ROOT / "dist" / "phase1-acquisition-manifest.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2))

if __name__ == "__main__":
    main()
