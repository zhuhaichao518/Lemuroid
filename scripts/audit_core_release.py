#!/usr/bin/env python3
"""Collect release evidence, pinned source archives and verbatim notices.

This is an evidence collector, not a license-compliance certification. Missing
revisions, build patches and recursive source dependencies require human review.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import zipfile


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def collect_source(core, output):
    revision = core.get("source_commit")
    if not revision:
        return {"id": core["id"], "error": "No verified source revision"}
    target = output / "sources" / f"{core['id']}-{revision}.tar.gz"
    url = f"https://codeload.github.com/{core['repository']}/tar.gz/{revision}"
    result = {"id": core["id"], "source_url": url,
              "provenance_status": core["status"], "notices": [], "submodules": []}
    try:
        if not target.exists():
            partial = target.with_suffix(target.suffix + ".partial")
            subprocess.run(["curl", "--fail", "--location", "--retry", "2",
                            "--retry-all-errors",
                            "--connect-timeout", "20", "--max-time", "240",
                            "--silent", "--show-error", url, "--output", str(partial)],
                           check=True)
            with tarfile.open(partial, "r:gz") as downloaded:
                downloaded.getmembers()
            partial.replace(target)
        result["archive_sha256"] = sha256(target.read_bytes())
        with tarfile.open(target, "r:gz") as archive:
            for member in archive.getmembers():
                if not member.isfile() or member.size > 2_000_000:
                    continue
                parts = Path(member.name).parts[1:]
                if not parts or any(part in ("..", "/") for part in parts):
                    continue
                name = parts[-1].lower()
                is_notice = (name.startswith(("license", "licence", "copying", "copyright", "notice"))
                             or name == "mamelicense.txt")
                if not is_notice and name != ".gitmodules":
                    continue
                data = archive.extractfile(member).read()
                destination = output / "notices" / core["id"] / Path(*parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                key = "notices" if is_notice else "submodules"
                result[key].append(str(Path(*parts)))
        if result["submodules"]:
            result["warning"] = "GitHub source archives omit submodule contents; recursive dependency audit required"
    except (subprocess.CalledProcessError, OSError, EOFError, tarfile.TarError) as error:
        result["error"] = str(error)
    print(f"{core['id']}: {len(result['notices'])} notice files; "
          f"{result.get('error', core['status'])}", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--download-sources", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "docs/release/core-provenance.json").read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "sources").mkdir(exist_ok=True)
    report = {"audit_status": "incomplete_do_not_publish",
              "apk_sha256": sha256(args.apk.read_bytes()), "binaries": [], "sources": []}
    if report["apk_sha256"] != manifest["apk_sha256"]:
        raise SystemExit("APK checksum differs from the audited release")
    with zipfile.ZipFile(args.apk) as apk:
        for core in manifest["cores"]:
            folder = root / "lemuroid-cores" / f"lemuroid_core_{core['id']}" / "src/main/jniLibs"
            for file in sorted(folder.glob("*/*.so")):
                abi = file.parent.name
                entry = f"lib/{abi}/{file.name}"
                data = file.read_bytes()
                contained = entry in apk.namelist()
                report["binaries"].append({
                    "core": core["id"], "abi": abi, "apk_path": entry,
                    "sha256": sha256(data), "included_in_apk": contained,
                    "apk_matches_submodule": contained and sha256(apk.read(entry)) == sha256(data),
                    "arm64_version_string_present":
                        core["runtime_version_arm64"].encode() + b"\0" in data,
                    "note": "A version string is evidence, not proof of an unmodified build",
                })
    if args.download_sources:
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            report["sources"] = list(executor.map(
                lambda core: collect_source(core, args.output), manifest["cores"]))
    (args.output / "core-audit.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Collected evidence for {len(report['binaries'])} core/ABI files.")
    print("Review core-audit.json; this collector never approves a release automatically.")


if __name__ == "__main__":
    main()
