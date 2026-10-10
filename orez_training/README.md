# Offline Orez Training Lab

Run from the repository root with Python 3.10+ on Linux (atomic no-replacement publication uses `renameat2`). Dataset preparation and evidence packaging use the standard library. Model commands additionally require locally installed `torch`, `transformers`, `peft`, `accelerate` and `safetensors`; they load existing local safetensors models with remote code and automatic downloads disabled. CUDA is used only when explicitly requested. Python remains outside Android's Reader/player hot path.

The commands implement a local workflow; no command has been run to train or qualify a model in this implementation batch. Host evaluation records actual outputs, errors, token termination and exact reference comparisons. Its result remains `HOST_TRANSFORMERS`, human quality review remains pending, and it cannot qualify Android native latency, RAM, thermal or multilingual behavior.

```
python -m orez_training build_dataset --sources /local/corpus/sources.json --output /local/runs/dataset
python -m orez_training train_lora --base /local/qwen-safetensors --dataset /local/runs/dataset/dataset-manifest.json --output /local/runs/lora --max-steps 500 --device cuda
python -m orez_training merge --base /local/qwen-safetensors --adapter /local/runs/lora/adapter --output /local/runs/merged
python -m orez_training eval --base /local/runs/merged/model --dataset /local/runs/dataset/dataset-manifest.json --output /local/runs/evaluation
python -m orez_training quantize --base /local/runs/merged/model --converter /local/llama.cpp/convert_hf_to_gguf.py --quantizer /local/llama.cpp/build/bin/llama-quantize --output /local/runs/gguf
python -m orez_training package_model --weights /local/runs/gguf/model.gguf --artifact DATASET_MANIFEST=/local/runs/dataset/dataset-manifest.json --artifact TRAINING_MANIFEST=/local/runs/lora/training-manifest.json --artifact EVALUATION_REPORT=/local/runs/evaluation/evaluation-report.json --artifact QUANTIZATION_MANIFEST=/local/runs/gguf/quantization-manifest.json --artifact MERGE_MANIFEST=/local/runs/merged/merge-manifest.json --artifact BASE_LICENSE=/local/base/LICENSE --artifact OUTPUT_LICENSE=/local/output/LICENSE --output /local/runs/packet
```

Supply `sources.json` with schema `orez-training-sources-v1` and explicit sources:

```json
{"schema":"orez-training-sources-v1","sources":[{"id":"authored-manga","file":"authored.jsonl","sha256":"<full input SHA-256>","revision":"<immutable revision>","license":"Authored-Permission","license_file":"permission.txt","license_sha256":"<actual permission document SHA-256>","training_permitted":true,"attribution":"<source attribution>"}]}
```

Each JSONL record contains `prompt`, `response` and an explicit `group` (for example, the source conversation or manga series). `language` and `category` are optional. Keep group identifiers consistent across sources. Preparation normalizes Unicode/whitespace, rejects missing/oversized records and common sensitive-data patterns, deduplicates prompt/response content on disk, and merges groups connected by duplicate content or repeated normalized prompt inputs before deterministic splitting. Pattern filters are bounded heuristics rather than proof that all personal information was removed. Inputs and license documents require exact hashes; no URL fetching occurs. An empty training or held-out split fails preparation. Add independent groups or choose another recorded seed rather than copying cases across splits.

The evidence ZIP contains a first `lab-manifest.json` plus bounded dataset/training/evaluation/license/code bodies. Actual GGUF bytes stay separate; their full hash/size are bound by the packet. Android's advanced Lab can store/export this packet as pending provenance; package import does not activate weights or change the production catalog. A completed host evaluation report and corresponding quantization manifest are required for packaging; it keeps its actual runtime scope. Output directories must be new so existing runs are preserved. Failed/cancelled runs retain a terminal status and their generated artifacts for inspection.
