#!/usr/bin/env python3
from __future__ import annotations
import argparse, gzip, hashlib, json, re
from pathlib import Path
from urllib.request import Request, urlopen

MIN=5*1024**3
MAX=10*1024**3
EMAIL=re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b",re.I)
PHONE=re.compile(r"(?<!\d)(?:\+?\d[\d .()\-]{7,}\d)(?!\d)")
GOV=re.compile(r"\b(?:aadhaar|ssn|social security|passport|pan)\b",re.I)
SECRET=re.compile(r"\b(?:api[_ -]?key|secret|private key|access token|password)\b",re.I)

def clean(v): return re.sub(r"\s+"," ",str(v).replace("\x00"," ")).strip()
def bad(s): return not s or len(s)>30000 or EMAIL.search(s) or PHONE.search(s) or GOV.search(s) or SECRET.search(s)
def role(v):
    x=clean(v).lower()
    return {"human":"user","user":"user","prompter":"user","gpt":"assistant","assistant":"assistant","bot":"assistant","system":"system"}.get(x,x)

def turns_from(raw):
    seq=raw.get("messages") or raw.get("conversations") or raw.get("input")
    out=[]
    if isinstance(seq,list):
        for t in seq:
            if not isinstance(t,dict): continue
            r=role(t.get("role",t.get("from","")))
            c=clean(t.get("content",t.get("value","")))
            if r in {"system","user","assistant"} and c: out.append({"speaker":r,"text":c})
    if not out:
        p=clean(raw.get("prompt",raw.get("question","")))
        a=clean(raw.get("response", raw.get("answer", raw.get("output", ""))))
        if p: out.append({"speaker":"user","text":p})
        if a: out.append({"speaker":"assistant","text":a})
    return out

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--source-id",required=True); ap.add_argument("--url",required=True)
    ap.add_argument("--license",required=True); ap.add_argument("--attribution",required=True)
    ap.add_argument("--output-dir",type=Path,required=True)
    ap.add_argument("--shard-bytes",type=int,default=1500*1024**2)
    ap.add_argument("--source-max-bytes",type=int,default=8*1024**3)
    a=ap.parse_args()
    a.output_dir.mkdir(parents=True,exist_ok=True)
    seen=set(); total=0; count=0; shards=[]; shard_bytes=0; out=None; gz=None; sha=None; idx=0
    def open_shard():
        nonlocal out,gz,sha,idx,shard_bytes
        if gz: gz.close()
        name=f"{a.source_id}-{idx:03d}.jsonl.gz"; idx+=1
        p=a.output_dir/name
        raw=p.open("wb"); gz=gzip.GzipFile(fileobj=raw,mode="wb",compresslevel=6)
        sha=hashlib.sha256(); shard_bytes=0
        shards.append({"name":name,"path":str(p),"uncompressed_bytes":0,"sha256":None})
        return raw
    raw_file=None
    req=Request(a.url,headers={"User-Agent":"MangaLens-OREZ-data-builder/1.0"})
    with urlopen(req,timeout=120) as src:
        raw_file=open_shard()
        for line in src:
            try: raw=json.loads(line)
            except Exception: continue
            if not isinstance(raw,dict): continue
            turns=turns_from(raw)
            users=[t for t in turns if t["speaker"]=="user"]; assists=[t for t in turns if t["speaker"]=="assistant"]
            if not users or not assists: continue
            prompt=users[-1]["text"]; response=assists[-1]["text"]
            full="\n".join(t["text"] for t in turns)
            if bad(full): continue
            key=hashlib.sha256(json.dumps([prompt,response,a.source_id],ensure_ascii=False).encode()).hexdigest()
            if key in seen: continue
            seen.add(key)
            rec={"type":"conversation","key":key,"domain":clean(raw.get("domain",raw.get("capability_target","general"))) or "general",
                 "language":clean(raw.get("language","en")) or "en","turns":turns,"prompt":prompt,"response":response,
                 "source":a.source_id,"source_record":clean(raw.get("uuid",raw.get("id",""))),
                 "license":a.license,"attribution":a.attribution,
                 "data_characterization":"source-provided human/synthetic/automated hybrid" if a.source_id.startswith("nvidia-") else "source-provided synthetic GPT-4 augmented",
                 "safety":{"pii_checked":True,"sensitive_filtered":True}}
            b=(json.dumps(rec,ensure_ascii=False,separators=(",",":"))+"\n").encode()
            if total+len(b)>a.source_max_bytes: break
            if shard_bytes and shard_bytes+len(b)>a.shard_bytes:
                gz.close(); raw_file.close()
                shards[-1]["uncompressed_bytes"]=shard_bytes; shards[-1]["sha256"]=sha.hexdigest()
                raw_file=open_shard()
            gz.write(b); gz.flush(); sha.update(b)
            shard_bytes+=len(b); total+=len(b); count+=1
        if gz:
            gz.close(); raw_file.close(); shards[-1]["uncompressed_bytes"]=shard_bytes; shards[-1]["sha256"]=sha.hexdigest()
    for s in shards: s["compressed_bytes"]=Path(s["path"]).stat().st_size; s.pop("path",None)
    if not shards or total==0: raise SystemExit("No valid records produced.")
    manifest={"format":"orez-conversation-jsonl-gzip-shards","format_version":2,"source":a.source_id,
              "license":a.license,"attribution":a.attribution,"generated_bytes":total,"record_count":count,
              "target_range_bytes":{"min":MIN,"max":MAX},"shards":shards}
    (a.output_dir/"source-manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(manifest,ensure_ascii=False,indent=2))
if __name__=="__main__": main()
