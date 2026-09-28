#!/usr/bin/env python3
from __future__ import annotations
import hashlib,json,re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
RAW=ROOT/"data"/"raw"
PHASE1=ROOT/"phase1-artifact"
DIST=ROOT/"dist"
OUT=DIST/"phase2-conversation-master.jsonl"
MAN=DIST/"phase2-clean-enrich-manifest.json"

PII=[
 re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b",re.I),
 re.compile(r"\b(?:\+?\d[\d .-]{7,}\d)\b"),
 re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
]
BAD=re.compile(r"(?i)\\b(?:password|passwd|api[_ -]?key|secret[_ -]?key)\\b\\s*[:=]\\s*\\S+")
SPACE=re.compile(r"[ \t\r\f\v]+")
REPEAT=re.compile(r"(.)\\1{9,}")

def clean(s):
 s=SPACE.sub(" ",str(s).replace("\\x00"," ")).strip()
 s=REPEAT.sub(r"\1\1\1",s)
 return s

def safe(s):
 s=clean(s)
 return bool(s) and len(s)<=12000 and not BAD.search(s) and not any(p.search(s) for p in PII)

def lang(s):
 if re.search(r"[\u0900-\u097F]",s): return "hi"
 if re.search(r"\b(acha|achha|kyun|kya|kaise|nahi|haan|bhai|tum|aap|mujhe|hai|ho)\b",s,re.I): return "hi-Latn"
 return "en"

def key(prompt,response):
 return hashlib.sha256((clean(prompt).lower()+"\n"+clean(response).lower()).encode()).hexdigest()

def iter_records():
 # Restore and process the authoritative Phase 1 corpus first.
 phase1_files=sorted(PHASE1.rglob("orez-conversation-master.jsonl")) if PHASE1.exists() else []
 for p in phase1_files:
  with p.open(encoding="utf-8",errors="replace") as f:
   for line in f:
    if line.strip():
     try: yield json.loads(line)
     except json.JSONDecodeError: continue
 for p in sorted(RAW.glob("*/normalized.jsonl")):
  with p.open(encoding="utf-8",errors="replace") as f:
   for line in f:
    if line.strip():
     try: yield json.loads(line)
     except json.JSONDecodeError: continue
 # Preserve the authored seed corpus in the final cleaned layer.
 for p in sorted((ROOT/"orez-pack"/"seed").glob("*.jsonl")):
  with p.open(encoding="utf-8",errors="replace") as f:
   for line in f:
    if line.strip():
     try: yield json.loads(line)
     except json.JSONDecodeError: continue

def main():
 DIST.mkdir(exist_ok=True)
 seen=set(); kept=0; rejected=0; counts={}; langs={}
 with OUT.open("w",encoding="utf-8") as out:
  for r in iter_records():
   prompt=clean(r.get("prompt","")); response=clean(r.get("response",""))
   if not safe(prompt) or not safe(response) or len(prompt)<2 or len(response)<2:
    rejected+=1; continue
   k=key(prompt,response)
   if k in seen: continue
   seen.add(k)
   source=str(r.get("key","unknown")).split(":",1)[0]
   domain=clean(r.get("domain","general")) or "general"
   language=lang(prompt+" "+response)
   rec={
    "type":"conversation","key":"phase2:"+k,"domain":domain,
    "language":language,
    "prompt":prompt,"response":response,
    "source":source,
    "safety":{"pii_checked":True,"sensitive_filtered":True},
   }
   out.write(json.dumps(rec,ensure_ascii=False,separators=(",",":"))+"\n")
   kept+=1; counts[source]=counts.get(source,0)+1; langs[language]=langs.get(language,0)+1
 manifest={
  "phase":"2","status":"cleaned-deduplicated-enriched",
  "records":kept,"rejected":rejected,"unique_keys":len(seen),
  "bytes":OUT.stat().st_size,"sources":counts,"languages":langs,
  "checks":["PII filtering","secret-pattern filtering","length normalization","duplicate removal","language tagging","authored seed preservation","phase1 corpus restoration"],
 }
 MAN.write_text(json.dumps(manifest,indent=2,ensure_ascii=False),encoding="utf-8")
 print(json.dumps(manifest,indent=2,ensure_ascii=False))
if __name__=="__main__": main()
