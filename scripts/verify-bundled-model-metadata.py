#!/usr/bin/env python3
"""Verify exact bundled publisher/runtime metadata; never establishes model quality."""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile

PINNED_ASSETS = {'orez/licenses/NOTICE.txt': {'sha256': '9233c13ea000da693d6fa02ffcd245a93f9af7414ab0f88fe69985b693689c8c', 'bytes': 1030}, 'orez/licenses/Qwen-GGUF-Apache-2.0.txt': {'sha256': '832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e', 'bytes': 11343}, 'orez/licenses/llama.cpp-MIT.txt': {'sha256': '94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d', 'bytes': 1078}, 'orez/model-governance/core-publisher.json': {'sha256': '09bd6b8e04b5eca58456a17e92e68a0fbbff1f0f00c8c0261793410db214c6e1', 'bytes': 8673}, 'orez/model-governance/legacy-publisher.json': {'sha256': '86efe4503306b84d8e967398972ec12a1fd30add0c18f81b3269da43d603cd46', 'bytes': 9060}, 'orez/model-governance/lite-publisher.json': {'sha256': 'dc413d2685ed7d0c39dfcff578d0bee2d0fa5fc982ed3a0ebd17c8287d6304af', 'bytes': 8822}, 'semantic/MODEL-CARD.md': {'sha256': 'dcd602d2fd35c203a247304a06fec6654a12f7941b739f9221a064fe8dc3b7f0', 'bytes': 10502}, 'semantic/model_config.json': {'sha256': '953f9c0d463486b10a6871cc2fd59f223b2c70184f49815e7efbcab5d8908b41', 'bytes': 612}, 'semantic/pooling_config.json': {'sha256': '4be450dde3b0273bb9787637cfbd28fe04a7ba6ab9d36ac48e92b11e350ffc23', 'bytes': 190}, 'semantic/public-metadata-receipt.json': {'sha256': '4ecb3bb930ce0390eed4d3b6915b9a3ff45001e7cf0d34f6393f1e765d65fe82', 'bytes': 1400}, 'semantic/tokenizer_config.json': {'sha256': 'acb92769e8195aabd29b7b2137a9e6d6e25c476a4f15aa4355c233426c61576b', 'bytes': 350}, 'semantic/vocab.txt': {'sha256': '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 'bytes': 231508}, 'third_party/onnxruntime-LICENSE.txt': {'sha256': '2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c', 'bytes': 1073}, 'third_party/onnxruntime-NOTICE.txt': {'sha256': '48170706e664fb4c47c3f26fc839d1b2423e958df71960b4388b754685f3d100', 'bytes': 687}, 'third_party/onnxruntime-ThirdPartyNotices.txt': {'sha256': 'cf7342f7ba482ef715ae58f5f497a8d3564fa255164175aea324cd293c5701a0', 'bytes': 338538}}

def verify_reader(read):
    for name, expected in PINNED_ASSETS.items():
        raw = read(name)
        if len(raw) != expected["bytes"] or hashlib.sha256(raw).hexdigest() != expected["sha256"]:
            raise RuntimeError(f"Bundled publisher metadata differs from its captured pin: {name}")
        if name.endswith(".json"):
            json.loads(raw.decode("utf-8"))
    return len(PINNED_ASSETS)

def verify_archive(archive):
    return verify_reader(lambda name: archive.read("assets/" + name))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets-dir", type=Path)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    if (args.assets_dir is None) == (args.apk is None):
        parser.error("Choose exactly one assets directory or APK")
    if args.assets_dir is not None:
        count = verify_reader(lambda name: (args.assets_dir / name).read_bytes())
    else:
        with zipfile.ZipFile(args.apk) as archive:
            count = verify_archive(archive)
    print(f"Verified {count} bundled publisher/runtime metadata pins; model inference and quality are separate checks.")
