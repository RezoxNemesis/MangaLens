"""Verify every Android model catalog pin against immutable publisher metadata.

Metadata only: this never downloads model weights or claims inference quality.
Use --offline for structural validation without a network connection.
"""
from __future__ import annotations

import argparse
import json
import re
import urllib.request
from pathlib import Path
from urllib.parse import urlparse


def read_catalog(source: str) -> list[dict]:
    models = []
    for match in re.finditer(r"val (\w+) = OrezModelDescriptor\((.*?)\n    \)", source, re.S):
        name, body = match.groups()
        strings = dict(re.findall(r'(\w+) = "([^"\n]+)"', body))
        size = re.search(r"bytes = (\d+)L", body)
        if not size:
            raise ValueError(f"Missing model size: {name}")
        url = urlparse(strings["url"])
        pin = re.fullmatch(r"/([^/]+/[^/]+)/resolve/([a-f0-9]{40})/([^/]+)", url.path)
        if url.scheme != "https" or url.netloc != "huggingface.co" or not pin:
            raise ValueError(f"Unpinned or unsupported model origin: {name}")
        repo, revision, filename = pin.groups()
        if Path(strings["fileName"]).name != strings["fileName"] or "\\" in strings["fileName"]:
            raise ValueError(f"Unsafe local model filename: {name}")
        if not re.fullmatch(r"[a-f0-9]{64}", strings["sha256"]):
            raise ValueError(f"Invalid model digest: {name}")
        model = dict(name=name, repo=repo, revision=revision, filename=filename,
                     bytes=int(size[1]), sha256=strings["sha256"])
        if model["bytes"] < 1_000_000:
            raise ValueError(f"Invalid model size: {name}")
        models.append(model)
    if not models or len({m["filename"] for m in models}) != len(models):
        raise ValueError("Empty catalog or duplicate model files")
    return models


def verify_publisher(model: dict) -> None:
    url = f'https://huggingface.co/api/models/{model["repo"]}/revision/{model["revision"]}?blobs=true'
    request = urllib.request.Request(url, headers={"User-Agent": "MangaLens-Orez-Evals"})
    with urllib.request.urlopen(request, timeout=45) as response:
        metadata = json.load(response)
    if metadata["sha"] != model["revision"]:
        raise ValueError("Publisher returned a different revision")
    entry = next(row for row in metadata["siblings"] if row["rfilename"] == model["filename"])
    lfs = entry["lfs"]
    if lfs["size"] != model["bytes"] or lfs.get("sha256", lfs.get("oid")) != model["sha256"]:
        raise ValueError(f'Publisher integrity metadata differs: {model["name"]}')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    catalog = read_catalog((root / "app/src/main/java/com/mangalens/orez/OrezModelCatalog.kt").read_text())
    for model in catalog:
        if not args.offline:
            verify_publisher(model)
        print(json.dumps(dict(model, verification="structure" if args.offline else "publisher-metadata")))


if __name__ == "__main__":
    main()
