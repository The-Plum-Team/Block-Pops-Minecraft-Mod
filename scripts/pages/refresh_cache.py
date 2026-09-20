#!/usr/bin/env python3
"""Validate one deployed cache using this attempt's authenticated branch companion."""

import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.pages import build_site as site  # noqa: E402


def validate_current_cache(api, args):
    """Validate the caller's digest-checked same-run collected bundle.

    A success summary is neither transport authority nor publication.
    """
    if site.REPO != REPO:
        raise site.SiteError("cache validator and protected renderer checkouts differ")
    with site._current_pages_inputs(api, args, phase="refresh") as context, ExitStack() as handles:
        rows = [row for row in context["rows"] if row["name"] == args.branch]
        if len(rows) != 1:
            raise site.SiteError("cache branch is not exactly enrolled")
        row = rows[0]
        root = args.input.absolute()
        site.atomic._real_directory(root.parent)
        root = root.parent.resolve(strict=True) / root.name
        parent = site.atomic._directory_fd(root.parent); handles.callback(os.close, parent)
        descriptor = site.atomic._directory_fd(Path(root.name), root_fd=parent); handles.callback(os.close, descriptor)
        raw, manifest_raw = context["capture"](root / "manifest.json")
        expected = dict(repository=args.repository, branch=args.branch,
            **{key: row[key] for key in ("commit", "tree", "matrix_sha256")})
        manifest = site._scoped_compact(root, row, manifest_raw, expected, context)
        if site.canonical_json(manifest) != site.canonical_json(raw):
            raise site.SiteError("cache manifest changed during validation")
        matrix_path = context["branches"][args.branch][0]
        payloads = {"manifest.json": manifest_raw, "release-matrix.json": matrix_path.read_bytes()}
        for record in manifest["files"]:
            _, data = site._child_file(root, record["path"], label="promoted cache", maximum=site.MAX_SITE_BYTES)
            if len(data) != record["size"] or site.sha256_bytes(data) != record["sha256"]:
                raise site.SiteError("cache payload changed after validation")
            payloads[record["path"]] = data
        final = []
        count, size = site._seal_output(descriptor, payloads, rechecks=final)
        site.atomic._bound_output_directory(root, parent, root.name, descriptor)
        context["recheck"]()
        final[0]()
        site.atomic._bound_output_directory(root, parent, root.name, descriptor)
        return dict(branch=args.branch, commit=row["commit"], tree=row["tree"],
            matrix_sha256=row["matrix_sha256"], files=count, bytes=size,
            **({"aggregate_scope": manifest["aggregate_scope"]} if "aggregate_scope" in manifest else {}))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "inventory"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("branch", "repository", "implementation-sha", "canonical-branch"):
        parser.add_argument("--" + name, required=True)
    for name in ("pages-run-id", "pages-run-attempt"):
        parser.add_argument("--" + name, type=int, required=True)
    args = parser.parse_args(argv)
    args.canonical_matrix = REPO / "release/release-matrix.json"
    try:
        api = site.GitHubApi(repository=args.repository, token=os.environ.get("GH_TOKEN", ""), api_url="https://api.github.com")
        result = validate_current_cache(api, args)
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RuntimeError, site.subprocess.SubprocessError) as exc:
        print(f"Pages cache error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
