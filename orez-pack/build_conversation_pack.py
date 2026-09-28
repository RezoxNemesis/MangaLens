#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, re
from pathlib import Path

MIN_BYTES = 5 * 1024**3
MAX_BYTES = 10 * 1024**3
EMAIL = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.I)
PHONE = re.compile(r"(?<!\d)(?:\+?\d[\d .()\-]{7,}\d)(?!\d)")
GOV = re.compile(r"\b(?:aadhaar|ssn|social security|passport|pan)\b", re.I)
SECRET = re.compile(r"\b(?:api[_ -]?key|secret|private key|access token|password)\b", re.I)

def clean(v):
    return re.sub(r"\s+", " ", str(v).replace("\x00", " ")).strip()

def reject(t):
    return not t or len(t) > 20000 or bool(EMAIL.search(t) or PHONE.search(t) or GOV.search(t) or SECRET.search(t))

def stable_key(p, r, l, s):
    return hashlib.sha256(json.dumps({"p": p, "r": r, "l": l, "s": s}, sort_keys=True, ensure_ascii=False).encode()).hexdigest()

def iter_records(path, source):
    if source.get("license") not in source.get("_allowed_licenses", set()):
        raise ValueError(f"Source {source['id']} has a license outside the allowlist: {source.get('license')}")
    if path.suffix.lower() not in {".json", ".jsonl", ".ndjson"}:
        return
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                raw = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(raw, dict):
                continue
            turns = []
            raw_turns = raw.get("turns", [])
            if isinstance(raw_turns, list):
                for t in raw_turns:
                    if isinstance(t, dict):
                        speaker = clean(t.get("speaker", t.get("role", ""))).lower()
                        text = clean(t.get("text", t.get("content", "")))
                        if speaker in {"user", "assistant", "system"} and text:
                            turns.append({"speaker": speaker, "text": text})
            prompt = clean(raw.get("prompt", "")) or (turns[-2]["text"] if len(turns) > 1 else "")
            response = clean(raw.get("response", "")) or (turns[-1]["text"] if turns else "")
            full = "\n".join([prompt, response] + [t["text"] for t in turns])
            if not prompt or not response or reject(full):
                continue
            lang = clean(raw.get("language", "und")) or "und"
            yield {
                "type": "conversation",
                "key": stable_key(prompt, response, lang, source["id"]),
                "domain": clean(raw.get("domain", "general")).lower() or "general",
                "language": lang,
                "turns": turns,
                "prompt": prompt,
                "response": response,
                "source": source["id"],
                "source_record": clean(raw.get("source_record", raw.get("id", ""))),
                "license": source["license"],
                "attribution": source.get("attribution", source["id"]),
                "safety": {"pii_checked": True, "sensitive_filtered": True},
            }

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", type=Path, default=Path("data/raw"))
    ap.add_argument("--output", type=Path, default=Path("dist/orez-conversation-master.jsonl"))
    ap.add_argument("--allow-below-target", action="store_true")
    a = ap.parse_args()

    pack_root = Path(__file__).resolve().parent
    policy = json.loads((pack_root / "conversation-sources.json").read_text(encoding="utf-8"))
    allowed = set(policy.get("license_policy", []))
    seen, total, count = set(), 0, 0
    a.output.parent.mkdir(parents=True, exist_ok=True)

    def emit(out, rec):
        nonlocal total, count
        if rec["key"] in seen:
            return
        line = json.dumps(rec, ensure_ascii=False, separators=(",", ":")) + "\n"
        b = line.encode("utf-8")
        if total + len(b) > MAX_BYTES:
            raise SystemExit("10 GiB ceiling reached; tighten source selection.")
        out.write(line)
        seen.add(rec["key"])
        total += len(b)
        count += 1

    with a.output.open("w", encoding="utf-8", newline="\n") as out:
        for source in policy["sources"]:
            source = dict(source)
            if not source.get("enabled", True):
                continue
            source["_allowed_licenses"] = allowed
            root = a.raw / source["id"]
            if root.exists():
                for p in sorted(root.rglob("*")):
                    if p.is_file():
                        for rec in iter_records(p, source):
                            emit(out, rec)

        seed_sources = [
            (pack_root / "seed/conversations_mangalens_seed.jsonl", "mangalens-orez-seed", "MangaLens OREZ seed corpus"),
            (pack_root / "seed/conversations_hinglish_priority.jsonl", "mangalens-orez-hinglish-seed", "MangaLens OREZ Hinglish seed corpus"),
            (pack_root / "seed/conversations_mangalens_core_hinglish.jsonl", "mangalens-orez-core-authored", "MangaLens OREZ authored core conversation corpus"),
        ]
        for seed, source_id, attribution in seed_sources:
            if not seed.is_file():
                continue
            source = {"id": source_id, "license": "MIT", "attribution": attribution, "_allowed_licenses": allowed}
            for rec in iter_records(seed, source):
                emit(out, rec)

    if total < MIN_BYTES and not a.allow_below_target:
        raise SystemExit(f"Only {total} bytes produced; add more genuine allowlisted data. No filler is generated.")

    digest = hashlib.sha256(a.output.read_bytes()).hexdigest()
    a.output.with_suffix(a.output.suffix + ".sha256").write_text(
        digest + "  " + a.output.name + "\n", encoding="utf-8"
    )
    manifest = {
        "format": "orez-conversation-jsonl",
        "format_version": 1,
        "pack_id": "orez-conversation-master",
        "kind": "conversation",
        "target_size_bytes": {"min": MIN_BYTES, "max": MAX_BYTES},
        "generated_bytes": total,
        "record_count": count,
        "sha256": digest,
        "delivery": "single UTF-8 JSONL file plus SHA-256 sidecar",
        "sources": policy["sources"],
        "target_reached": MIN_BYTES <= total <= MAX_BYTES,
    }
    Path("dist/conversation-pack-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
