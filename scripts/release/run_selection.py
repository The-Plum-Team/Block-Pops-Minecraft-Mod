"""Select current exact-head producer runs; this is not lane qualification."""

import re

from scripts.lib.secure_json import canonical_json
from scripts.release.github_api import GitHubApi, SelectionError, _run_order, _validate_run
from scripts.release.matrix import valid_branch_name

WORKFLOWS = {"build-gate.yml": "Build gate", "on-demand-e2e.yml": "Packaged E2E"}
RUN_FIELDS = ("id", "run_attempt", "created_at", "status", "conclusion", "workflow_id", "path",
              "event", "display_title", "head_branch", "head_sha")


def _check(condition, message):
    if not condition:
        raise SelectionError(message)


def _snapshot(run):
    return canonical_json({**{key: run[key] for key in RUN_FIELDS},
        **{key: run[key]["full_name"] for key in ("repository", "head_repository")}})


def select_current_run(api: GitHubApi, *, repository: str, canonical_branch: str,
                       controller_sha: str, branch: str, commit: str, tree: str,
                       workflow: str) -> dict:
    """Read API-owned run state under externally authenticated controller/source inputs.

    The caller must authenticate the controller generation and matrix separately.
    No local receipt can replace these live API reads. Invoke again after acquiring
    artifacts and validating job/bundle/build/E2E evidence, before planning a release.
    The returned identity alone grants no qualification or publication authority.
    """
    try:
        _check(workflow in WORKFLOWS and api.repository == repository, "unsupported workflow or crossed API repository")
        _check(valid_branch_name(branch) and valid_branch_name(canonical_branch), "invalid source/controller branch")
        _check(all(isinstance(value, str) and re.fullmatch("[0-9a-f]{40}", value)
                   for value in (controller_sha, commit, tree)), "invalid source/controller object identity")
        _check(branch != canonical_branch or commit == controller_sha,
               "canonical source and controller must identify the same commit")
        def current_branches():
            _check(api.branch_head(branch) == (commit, tree), "source branch advanced or tree differs")
            if branch != canonical_branch:
                observed = api.branch_head(canonical_branch)
                _check(isinstance(observed, tuple) and len(observed) == 2 and observed[0] == controller_sha,
                       "protected controller branch advanced")
        current_branches()
        record = api.workflow(workflow)
        workflow_id, path = record["id"], ".github/workflows/" + workflow
        _check(type(workflow_id) is int and workflow_id > 0 and record["path"] == path
               and record["state"] == "active", "workflow API identity is not active/exact")
        events = ({"workflow_dispatch", "push"} if workflow == "build-gate.yml"
                  else {"workflow_dispatch", "schedule"}) if branch == canonical_branch else {"workflow_dispatch"}
        title = WORKFLOWS[workflow] + " / " + commit
        def validate(run, *, expected_controller, success):
            _check(isinstance(run, dict) and type(run.get("workflow_id")) is int,
                   "invalid exact run workflow identity")
            _run_order(run)
            _check(isinstance(run.get("repository"), dict) and run["repository"].get("full_name") == repository,
                   "exact run belongs to another repository")
            _validate_run(run, workflow_id=workflow_id, workflow_path=path, repository=repository,
                branch=canonical_branch, sha=expected_controller, events=frozenset(events),
                require_success=success, display_title=title)
            return run
        def newest():
            rows = api.runs(workflow_id, canonical_branch)
            _check(isinstance(rows, list) and len(rows) <= 1000 and all(isinstance(row, dict) for row in rows),
                   "invalid or oversized workflow run inventory")
            candidates, seen = [], set()
            for row in rows:
                if row.get("display_title") != title or row.get("head_branch") != canonical_branch:
                    continue
                validate(row, expected_controller=row.get("head_sha"), success=False)
                _check(row["id"] not in seen, "duplicate exact producer run")
                seen.add(row["id"]); candidates.append(row)
            _check(bool(candidates), "no exact-head producer run exists")
            # Select before enforcing controller or success; a newer wrong/failed
            # producer must never expose an older successful run as current.
            return validate(max(candidates, key=_run_order), expected_controller=controller_sha, success=True)
        chosen = newest()
        snapshot = _snapshot(chosen)
        for observed in (api.run(chosen["id"]), api.run_attempt(chosen["id"], chosen["run_attempt"])):
            validate(observed, expected_controller=controller_sha, success=True)
            _check(_snapshot(observed) == snapshot, "producer attempt changed during authentication")
        _check(_snapshot(newest()) == snapshot, "newer producer appeared during authentication")
        current_branches()
        return {"workflow": workflow, "workflow_id": workflow_id, "run_id": chosen["id"],
                "run_attempt": chosen["run_attempt"], "event": chosen["event"], "created_at": chosen["created_at"],
                "repository": repository, "branch": branch, "commit": commit, "tree": tree,
                "controller_branch": canonical_branch, "controller_sha": controller_sha}
    except (KeyError, TypeError, ValueError, AttributeError) as exc:
        raise SelectionError(f"invalid current producer evidence: {exc}") from exc
