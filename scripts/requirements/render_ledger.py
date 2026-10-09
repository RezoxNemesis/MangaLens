#!/usr/bin/env python3
"""Render curated requirement evidence; never infer implementation or test success.

The JSON records a manual source audit and separate candidate evidence. This
command validates its identities, local source paths and contract hash, then
renders documentation. It does not run builds or automatically re-audit code.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def render(repo: Path, data_path: Path, output: Path) -> None:
    data = json.loads(data_path.read_text())
    records = data["requirements"]
    ids = [item["id"] for item in records]
    if len(ids) != len(set(ids)):
        raise ValueError("Requirement IDs must be unique")
    contract = repo / data["contract_path"]
    if hashlib.sha256(contract.read_bytes()).hexdigest() != data["contract_sha256"]:
        raise ValueError("Contract hash differs from the audited user blueprint")
    valid_statuses = {"implemented/verified", "implemented/unverified", "partial", "planned", "blocked"}
    for index in data["evidence_index"].values():
        for path in index["source"] + index["tests"]:
            target = (repo / path).resolve()
            if not target.is_relative_to(repo) or not target.is_file():
                raise ValueError("Related source/test path is unavailable or escapes the repository: " + path)
    for record in records:
        if record["status"] not in valid_statuses:
            raise ValueError("Unknown requirement status: " + record["id"])
        if record["status"] == "implemented/verified" and not record.get("candidate_evidence_ids"):
            raise ValueError("Verified status requires named current candidate evidence: " + record["id"])
        if any(area not in data["evidence_index"] for area in record["areas"]):
            raise ValueError("Unknown related-source index: " + record["id"])
    counts = dict(collections.Counter(record["status"] for record in records))
    parts = [
        "# MangaLens requirements ledger\n\n",
        f"Source audit: 9 October 2026, mature `engineering/mangalens-next-orez-foundation` at `{data['source_head']}`. "
        "That checkout was clean before concurrent candidate fixes. This baseline audit is separate from the candidate evidence appendix; later source changes require their own evidence. "
        "The initial baseline audit ran no build, emulator suite or model evaluation; subsequent executed candidate checks are recorded separately below.\n\n",
        f"The entire 3,659-line [user blueprint](spec/{contract.name}) was read. SHA-256: `{data['contract_sha256']}`. "
        f"**{len(records)} individual rows** retain all 1,652 original IDs, add normative prose and current feature/emulator matrices, and refresh all 18 historical prototype identities. "
        "The prior baseline ledger is retained in Git history. "
        "The [machine-readable audit](evidence/requirements-audit.json) is the curated evidence record; "
        "`python3 scripts/requirements/render_ledger.py` reproduces this document without changing claims.\n\n",
        "Status meanings: **implemented/unverified** means a concrete source path exists, with current-candidate runtime/quality acceptance outstanding; "
        "**partial** means some behavior exists but the full contract is incomplete; **planned** means no integrated path was located; "
        "**blocked** names a specific missing prerequisite or archival input; **implemented/verified** requires named current candidate output, failure/recovery, quality and applicable direct emulator evidence. "
        "Related source links for partial/planned/blocked rows identify extension points, not implementation proof for every tool or clause. "
        "Historical inventories and compile-only tests never establish current runtime success.\n\n",
        f"Status counts: `{json.dumps(counts, sort_keys=True)}`. "
        "Each requested behavior's acceptance is its real UI/runtime result, meaningful failure/cancellation/recovery, intact persistence/privacy/neighbors, appropriate build/package gates and direct emulator/output-quality evidence tied to source/APK identity. "
        "Section-contract `.C` rows include their prose and conceptual schemas. Source hashes are recorded for the audited baseline; links may display newer working files.\n\n",
        "## Priorities and preserved stage recovery\n\n",
        data["priority_summary"],
        "\n\n## Privacy, costs and missing inputs\n\n",
        data["boundary_summary"],
        "\n\n## Related source and tests\n\n",
    ]
    for area, index in data["evidence_index"].items():
        parts.append(f"### Evidence {area}\n\n{data['area_audit'][area]}\n\n")
        def links(paths):
            return "; ".join(f"[{Path(path).name}](../{path})" for path in paths)
        parts.append("Related source / extension points: " + links(index["source"]) + ".\n\n")
        parts.append("Related tests/tooling, not executed by this audit: " + (links(index["tests"]) or "No dedicated path indexed") + ".\n\n")
    parts.append("## Individually addressed requirements\n\n")
    group = None
    def esc(value):
        return str(value).replace("|", "\\|").replace("\n", " ")
    for record in records:
        if record["section"] != group:
            group = record["section"]
            parts.append(f"### {group}\n\n| ID / contract line | Required behavior | Status | Related source and acceptance |\n|---|---|---|---|\n")
        line = record.get("brief_line")
        location = f"[L{line}](spec/{contract.name}#L{line})" if line else "full section"
        related = ", ".join(f"[{area}](#evidence-{area})" for area in record["areas"])
        label = "Source path; current acceptance pending" if record["status"] == "implemented/unverified" else "Related paths; complete acceptance pending"
        detail = record.get("specific_binding") or (record["audit"] if record["id"].startswith("B.") else label)
        evidence = ", ".join(record.get("candidate_evidence_ids", []))
        if evidence:
            detail += "; candidate evidence " + evidence
        parts.append(f"| `{record['id']}` {location} | {esc(record['requirement'])} | {record['status']} | {related}. {esc(detail)}. |\n")
    parts.append("\n## Historical prototype identity\n\nThe current source supersedes the stopped prototype; missing historical bytes are archival blockers and do not justify overwriting newer integrated work. Checksums prove identity, not correctness.\n\n| Original path | Historical SHA-256 | Audited baseline SHA-256 | Identical |\n|---|---|---|---|\n")
    for row in data["historical_prototype_inventory"]:
        parts.append(f"| `{row['path']}` | `{row['historical_sha256']}` | " + (f"`{row['current_sha256']}`" if row["current_sha256"] else "absent") + f" | {str(row['historical_version_available']).lower()} |\n")
    parts.append("\n## Current candidate evidence appendix\n\nThis appendix is updated only after current-candidate verification. Baseline source audit statuses remain distinguishable from new candidate outcomes.\n\n| Evidence ID | Candidate source / APK | Executed check and outcome | Report / limitations |\n|---|---|---|---|\n")
    if data.get("candidate_evidence"):
        for row in data["candidate_evidence"]:
            parts.append("| " + " | ".join(esc(row.get(key, "")) for key in ["id", "candidate", "outcome", "report_and_limits"]) + " |\n")
    else:
        parts.append("| PENDING | Current candidate fixes in progress | No fresh result asserted by the requirements auditor | Root owns reports, APK identity, emulator/CI execution and evidence promotion. |\n")
    parts.append("\n## Required candidate deliverables\n\nExact source SHA/dirty status/version/channel/CI; unit/lint/native/ABI/archive/signature reports; installable ARM64 and x86_64 APKs with sizes/SHA-256; emulator API/ABI/profile/acceleration/installed identity; passed/failed/blocked feature and fault matrix; screenshots/logcat/failure recordings/output samples; startup/memory/processing/transfer/playback/inference measurements; model/provider versions/hashes/licenses; crash/ANR and hardware-only limits; traceable commits/draft PR; refreshed ledger and continuity with exact next commands. Full application completion requires every applicable contract and current acceptance gate to be addressed.\n")
    output.write_text("".join(parts))
    print(json.dumps({"rows": len(records), "statuses": counts, "output": str(output)}, sort_keys=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--data", type=Path, default=Path("docs/evidence/requirements-audit.json"))
    parser.add_argument("--output", type=Path, default=Path("docs/MANGALENS_REQUIREMENTS_LEDGER.md"))
    args = parser.parse_args()
    repo = args.repo.resolve()
    render(repo, repo / args.data, repo / args.output)
