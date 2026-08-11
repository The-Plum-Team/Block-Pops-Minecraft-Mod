#!/usr/bin/env python3
"""Authenticate and select one item from the durable visual-review queue.

Queue state is represented by immutable GitHub artifacts.  An artifact name is
only an index: its API id, digest, size, producer run, and producer workflow are
authenticated before it can affect selection or cleanup.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Protocol


REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
IDENTITY_SUFFIX = (
    r"(?P<source>[1-9][0-9]*)-"
    r"(?P<attempt>[1-9][0-9]*)-"
    r"(?P<sha>[0-9a-f]{40})"
)
INPUT_NAME = re.compile(
    rf"^visual-review-input-{IDENTITY_SUFFIX}-"
    r"(?P<producer_attempt>[1-9][0-9]*)$"
)
REPORT_NAME = re.compile(rf"^visual-review-{IDENTITY_SUFFIX}$")
ATTEMPT_NAME = re.compile(
    rf"^visual-review-attempt-{IDENTITY_SUFFIX}-(?P<ordinal>[12])$"
)
COOLDOWN_NAME = re.compile(
    rf"^visual-review-cooldown-{IDENTITY_SUFFIX}-"
    r"(?P<ordinal>[12])-(?P<delay>[1-9][0-9]{0,4})$"
)
PREPARE_WORKFLOW = ".github/workflows/visual-review.yml"
DRAIN_WORKFLOW = ".github/workflows/visual-review-drain.yml"
SOURCE_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
PREPARE_EVENTS = frozenset({"repository_dispatch", "workflow_run"})
DRAIN_EVENTS = frozenset({"repository_dispatch", "schedule", "workflow_dispatch"})
SOURCE_EVENTS = frozenset({"pull_request_target", "workflow_dispatch"})
TERMINAL_CONCLUSIONS = frozenset(
    {
        "action_required",
        "cancelled",
        "failure",
        "neutral",
        "skipped",
        "stale",
        "startup_failure",
        "success",
        "timed_out",
    }
)
MAX_ARTIFACTS = 10_000
MAX_QUEUE_INPUTS = 8
MAX_INPUT_BYTES = 96 * 1024 * 1024
MAX_RELEVANT_BYTES = 256 * 1024 * 1024
MAX_DRAIN_ATTEMPTS = 2
MAX_COOLDOWN_SECONDS = 21_600
MAX_API_BYTES = 16 * 1024 * 1024
DEFAULT_COOLDOWN_MINUTES = 30

RETAINING_OUTCOMES = frozenset(
    {
        "authentication",
        "attempts_exhausted",
        "configuration",
        "external_capacity",
        "provider",
        "transient_network",
        "unknown",
    }
)
TERMINAL_OUTCOMES = frozenset(
    {"reviewed", "stale_source", "terminal_input_rejected"}
)


class QueueError(RuntimeError):
    """Raised when queue state cannot be authenticated unambiguously."""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keep the GitHub bearer on its exact canonical API request only."""

    def redirect_request(
        self, request: Any, fp: Any, code: int, message: str, headers: Any, newurl: str
    ) -> None:
        return None


@dataclass(frozen=True, order=True)
class ReviewIdentity:
    source_run_id: int
    source_run_attempt: int
    tested_sha: str

    def __post_init__(self) -> None:
        _positive_int(self.source_run_id, "source run id")
        _positive_int(self.source_run_attempt, "source run attempt")
        if not isinstance(self.tested_sha, str) or not SHA.fullmatch(self.tested_sha):
            raise QueueError("tested SHA must be 40 lowercase hexadecimal characters")


@dataclass(frozen=True)
class Artifact:
    artifact_id: int
    name: str
    size_in_bytes: int
    digest: str
    expired: bool
    created_at: datetime
    producer_run_id: int
    producer_head_branch: str
    producer_head_sha: str

    def __post_init__(self) -> None:
        _positive_int(self.artifact_id, "artifact id")
        _positive_int(self.size_in_bytes, "artifact size")
        _positive_int(self.producer_run_id, "producer run id")
        if not isinstance(self.name, str) or not self.name:
            raise QueueError("artifact name must be non-empty")
        if not isinstance(self.digest, str) or not DIGEST.fullmatch(self.digest):
            raise QueueError("artifact digest must be a lowercase SHA-256 digest")
        if not isinstance(self.expired, bool):
            raise QueueError("artifact expired state must be boolean")
        if not isinstance(self.created_at, datetime) or self.created_at.tzinfo is None:
            raise QueueError("artifact creation time must include a timezone")
        if (
            not isinstance(self.producer_head_branch, str)
            or not self.producer_head_branch
            or not isinstance(self.producer_head_sha, str)
            or not SHA.fullmatch(self.producer_head_sha)
        ):
            raise QueueError("artifact producer ref is invalid")

    @property
    def order(self) -> tuple[datetime, int]:
        return (self.created_at, self.artifact_id)


@dataclass(frozen=True)
class Selection:
    identity: ReviewIdentity
    artifact: Artifact
    producer_run_attempt: int
    queue_state: str
    completed_attempts: int
    next_attempt_ordinal: int | None


@dataclass(frozen=True)
class OwnerEvidence:
    run_attempt: int
    active: bool


@dataclass(frozen=True)
class CleanupTarget:
    artifact_id: int
    name: str
    digest: str
    size_in_bytes: int
    created_at: datetime
    producer_run_id: int
    producer_head_branch: str
    producer_head_sha: str

    @classmethod
    def from_artifact(cls, artifact: Artifact) -> "CleanupTarget":
        return cls(
            artifact_id=artifact.artifact_id,
            name=artifact.name,
            digest=artifact.digest,
            size_in_bytes=artifact.size_in_bytes,
            created_at=artifact.created_at,
            producer_run_id=artifact.producer_run_id,
            producer_head_branch=artifact.producer_head_branch,
            producer_head_sha=artifact.producer_head_sha,
        )


@dataclass(frozen=True)
class CleanupPlan:
    artifact_id: int
    delete: bool
    already_absent: bool


class QueueApi(Protocol):
    def list_artifacts(self) -> list[Artifact]: ...

    def get_run(self, run_id: int) -> dict[str, Any]: ...

    def get_run_attempt(self, run_id: int, run_attempt: int) -> dict[str, Any]: ...


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise QueueError(f"{label} must be a positive integer")
    return value


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise QueueError(f"{label} must be a non-empty string")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    text = _text(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise QueueError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise QueueError(f"{label} must include a timezone")
    return parsed


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise QueueError(f"GitHub API JSON repeats key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise QueueError(f"GitHub API JSON contains non-finite number {value!r}")


def _canonical_api_repository_url(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    parsed = urllib.parse.urlsplit(value)
    parts = parsed.path.split("/")
    return bool(
        parsed.scheme == "https"
        and parsed.netloc == "api.github.com"
        and not parsed.query
        and not parsed.fragment
        and len(parts) == 4
        and parts[0] == ""
        and parts[1] == "repos"
        and all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in parts[2:])
    )


def parse_identity(name: str, pattern: re.Pattern[str]) -> ReviewIdentity | None:
    match = pattern.fullmatch(name)
    if match is None:
        return None
    return ReviewIdentity(
        int(match.group("source")),
        int(match.group("attempt")),
        match.group("sha"),
    )


def parse_producer_attempt(name: str) -> int | None:
    match = INPUT_NAME.fullmatch(name)
    if match is None:
        return None
    return int(match.group("producer_attempt"))


def parse_attempt_ordinal(name: str) -> int | None:
    match = ATTEMPT_NAME.fullmatch(name)
    return None if match is None else int(match.group("ordinal"))


def parse_cooldown(name: str) -> tuple[ReviewIdentity, int, int] | None:
    """Parse one canonical cooldown name and enforce its delay bound."""

    match = COOLDOWN_NAME.fullmatch(name)
    if match is None:
        return None
    delay_seconds = int(match.group("delay"))
    if not 1 <= delay_seconds <= MAX_COOLDOWN_SECONDS:
        raise QueueError(
            f"visual cooldown must be between 1 and {MAX_COOLDOWN_SECONDS} seconds"
        )
    identity = ReviewIdentity(
        int(match.group("source")),
        int(match.group("attempt")),
        match.group("sha"),
    )
    return identity, int(match.group("ordinal")), delay_seconds


def parse_artifact(value: Any) -> Artifact:
    if not isinstance(value, dict):
        raise QueueError("artifact must be an object")
    workflow_run = value.get("workflow_run")
    if not isinstance(workflow_run, dict):
        raise QueueError("artifact.workflow_run must be an object")
    digest = _text(value.get("digest"), "artifact.digest")
    head_sha = _text(workflow_run.get("head_sha"), "artifact.workflow_run.head_sha")
    if not DIGEST.fullmatch(digest) or not SHA.fullmatch(head_sha):
        raise QueueError("artifact has an invalid digest or producer SHA")
    expired = value.get("expired")
    if not isinstance(expired, bool):
        raise QueueError("artifact.expired must be a boolean")
    return Artifact(
        artifact_id=_positive_int(value.get("id"), "artifact.id"),
        name=_text(value.get("name"), "artifact.name"),
        size_in_bytes=_positive_int(value.get("size_in_bytes"), "artifact.size"),
        digest=digest,
        expired=expired,
        created_at=_timestamp(value.get("created_at"), "artifact.created_at"),
        producer_run_id=_positive_int(
            workflow_run.get("id"), "artifact.workflow_run.id"
        ),
        producer_head_branch=_text(
            workflow_run.get("head_branch"), "artifact.workflow_run.head_branch"
        ),
        producer_head_sha=head_sha,
    )


def authenticate_owner(
    run: Any,
    *,
    repository: str,
    default_branch: str,
    artifact: Artifact,
    workflow: str,
    events: frozenset[str],
    conclusions: frozenset[str],
    allow_in_progress: bool = False,
) -> OwnerEvidence | None:
    """Return the exact producer attempt, or None for unauthenticated metadata."""

    if not isinstance(run, dict):
        return None
    attempt = run.get("run_attempt")
    if isinstance(attempt, bool) or not isinstance(attempt, int) or attempt <= 0:
        return None
    conclusion = run.get("conclusion")
    event = run.get("event")
    terminal = (
        run.get("status") == "completed"
        and isinstance(conclusion, str)
        and conclusion in conclusions
    )
    active = (
        allow_in_progress
        and run.get("status") == "in_progress"
        and run.get("conclusion") is None
    )
    if not terminal and not active:
        return None
    if not isinstance(run.get("repository"), dict) or not isinstance(
        run.get("head_repository"), dict
    ):
        return None
    if (
        isinstance(run.get("id"), bool)
        or not isinstance(run.get("id"), int)
        or run.get("id") != artifact.producer_run_id
        or not isinstance(event, str)
        or event not in events
        or run.get("path") != workflow
        or run.get("head_branch") != default_branch
        or run.get("head_sha") != artifact.producer_head_sha
        or run["repository"].get("full_name") != repository
        or run["head_repository"].get("full_name") != repository
    ):
        return None
    return OwnerEvidence(attempt, active)


def authenticate_source_identity(
    run: Any, *, repository: str, identity: ReviewIdentity
) -> bool:
    """Authenticate the historical protected-controller run without SHA conflation.

    ``pull_request_target`` and protected workflow dispatches expose the default
    controller as the run head, never the candidate commit named by the queue.
    The tested commit remains authenticated by the protected queue manifest and
    the later PR/tree reauthentication.
    """

    if not isinstance(run, dict):
        return False
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        return False
    run_id = run.get("id")
    run_attempt = run.get("run_attempt")
    event = run.get("event")
    repository_record = run.get("repository")
    repository_url = f"https://api.github.com/repos/{repository}"
    common = bool(
        type(run_id) is int
        and run_id == identity.source_run_id
        and type(run_attempt) is int
        and run_attempt == identity.source_run_attempt
        and run.get("path") == SOURCE_WORKFLOW
        and isinstance(event, str)
        and event in SOURCE_EVENTS
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
        and isinstance(repository_record, dict)
        and repository_record.get("full_name") == repository
        and repository_record.get("url") == repository_url
        and run.get("head_branch") == "master"
        and isinstance(run.get("head_sha"), str)
        and SHA.fullmatch(run["head_sha"])
    )
    if not common:
        return False
    run_head_repository = run.get("head_repository")
    if (
        not isinstance(run_head_repository, dict)
        or run_head_repository.get("id") != repository_record.get("id")
        or run_head_repository.get("url") != repository_url
    ):
        return False
    if event != "pull_request_target":
        return True

    pull_requests = run.get("pull_requests")
    if not isinstance(pull_requests, list) or len(pull_requests) != 1:
        return False
    pull = pull_requests[0]
    if not isinstance(pull, dict):
        return False
    pull_id = pull.get("id")
    pull_number = pull.get("number")
    head = pull.get("head")
    base = pull.get("base")
    if (
        type(pull_id) is not int
        or pull_id <= 0
        or type(pull_number) is not int
        or pull_number <= 0
        or pull.get("url") != f"{repository_url}/pulls/{pull_number}"
        or not isinstance(head, dict)
        or not isinstance(base, dict)
    ):
        return False
    head_repo = head.get("repo")
    base_repo = base.get("repo")
    return bool(
        isinstance(head.get("sha"), str)
        and SHA.fullmatch(head["sha"])
        and isinstance(head.get("ref"), str)
        and bool(head["ref"])
        and isinstance(head_repo, dict)
        and head_repo.get("id") == repository_record.get("id")
        and head_repo.get("url") == repository_url
        and isinstance(base.get("sha"), str)
        and SHA.fullmatch(base["sha"])
        and isinstance(base.get("ref"), str)
        and bool(base["ref"])
        and isinstance(base_repo, dict)
        and base_repo.get("url") == repository_url
        and type(repository_record.get("id")) is int
        and repository_record["id"] > 0
        and base_repo.get("id") == repository_record["id"]
    )


def _authenticated_by_identity(
    api: QueueApi,
    artifacts: list[Artifact],
    *,
    repository: str,
    default_branch: str,
    pattern: re.Pattern[str],
    workflow: str,
    events: frozenset[str],
    conclusions: frozenset[str],
    allow_in_progress: bool = False,
) -> dict[ReviewIdentity, list[tuple[Artifact, OwnerEvidence]]]:
    selected: dict[ReviewIdentity, list[tuple[Artifact, OwnerEvidence]]] = {}
    run_cache: dict[tuple[int, int | None], dict[str, Any]] = {}
    for artifact in artifacts:
        identity = parse_identity(artifact.name, pattern)
        if identity is None or artifact.expired:
            continue
        producer_attempt = (
            parse_producer_attempt(artifact.name) if pattern is INPUT_NAME else None
        )
        cache_key = (artifact.producer_run_id, producer_attempt)
        run = run_cache.get(cache_key)
        if run is None:
            if producer_attempt is None:
                run = api.get_run(artifact.producer_run_id)
            else:
                run = api.get_run_attempt(
                    artifact.producer_run_id, producer_attempt
                )
            run_cache[cache_key] = run
        owner = authenticate_owner(
            run,
            repository=repository,
            default_branch=default_branch,
            artifact=artifact,
            workflow=workflow,
            events=events,
            conclusions=conclusions,
            allow_in_progress=allow_in_progress,
        )
        if owner is not None:
            if producer_attempt is not None and owner.run_attempt != producer_attempt:
                raise QueueError(
                    "queue producer attempt endpoint returned another attempt"
                )
            selected.setdefault(identity, []).append((artifact, owner))
    return selected


def _validate_inventory(artifacts: list[Artifact]) -> None:
    if len(artifacts) > MAX_ARTIFACTS:
        raise QueueError(f"visual artifact inventory exceeds {MAX_ARTIFACTS}")
    ids: set[int] = set()
    for artifact in artifacts:
        if artifact.artifact_id in ids:
            raise QueueError("visual artifact inventory repeats an artifact id")
        ids.add(artifact.artifact_id)


def _validate_authenticated_inventory(
    api: QueueApi,
    artifacts: list[Artifact],
    *,
    repository: str,
    default_branch: str,
    groups: tuple[dict[ReviewIdentity, list[tuple[Artifact, OwnerEvidence]]], ...],
) -> None:
    """Apply queue caps only after the protected producer has been authenticated."""

    trusted: dict[int, Artifact] = {}
    for group in groups:
        for values in group.values():
            for artifact, _owner in values:
                trusted[artifact.artifact_id] = artifact
    relevant_bytes = sum(artifact.size_in_bytes for artifact in trusted.values())
    if relevant_bytes > MAX_RELEVANT_BYTES:
        raise QueueError("visual artifact inventory exceeds the byte bound")
    queue_inputs = sum(
        1 for artifact in trusted.values() if INPUT_NAME.fullmatch(artifact.name)
    )
    if queue_inputs > MAX_QUEUE_INPUTS:
        raise QueueError("visual input queue exceeds the entry bound")

    run_cache: dict[int, dict[str, Any]] = {}
    for artifact in artifacts:
        if artifact.expired:
            continue
        policy: tuple[re.Pattern[str], str, frozenset[str], frozenset[str], bool] | None
        if artifact.name.startswith("visual-review-input-"):
            policy = (
                INPUT_NAME,
                PREPARE_WORKFLOW,
                PREPARE_EVENTS,
                frozenset({"success"}),
                False,
            )
        elif artifact.name.startswith("visual-review-attempt-"):
            policy = (
                ATTEMPT_NAME,
                DRAIN_WORKFLOW,
                DRAIN_EVENTS,
                TERMINAL_CONCLUSIONS,
                True,
            )
        elif artifact.name.startswith("visual-review-cooldown-"):
            policy = (
                COOLDOWN_NAME,
                DRAIN_WORKFLOW,
                DRAIN_EVENTS,
                TERMINAL_CONCLUSIONS,
                True,
            )
        elif re.match(r"^visual-review-[1-9][0-9]*-", artifact.name) is not None:
            policy = (
                REPORT_NAME,
                DRAIN_WORKFLOW,
                DRAIN_EVENTS,
                TERMINAL_CONCLUSIONS,
                False,
            )
        else:
            policy = None
        if policy is None or policy[0].fullmatch(artifact.name):
            continue
        run = run_cache.get(artifact.producer_run_id)
        if run is None:
            run = api.get_run(artifact.producer_run_id)
            run_cache[artifact.producer_run_id] = run
        owner = authenticate_owner(
            run,
            repository=repository,
            default_branch=default_branch,
            artifact=artifact,
            workflow=policy[1],
            events=policy[2],
            conclusions=policy[3],
            allow_in_progress=policy[4],
        )
        if owner is not None:
            raise QueueError("visual queue contains a malformed trusted reserved artifact name")


def select_pending(
    api: QueueApi,
    *,
    repository: str,
    default_branch: str,
    now: datetime | None = None,
    cooldown: timedelta = timedelta(minutes=DEFAULT_COOLDOWN_MINUTES),
) -> Selection | None:
    """Select the oldest eligible identity without cooldown head-of-line blocking."""

    if (
        not isinstance(repository, str)
        or not REPOSITORY.fullmatch(repository)
        or not isinstance(default_branch, str)
        or not default_branch
    ):
        raise QueueError("repository and default branch must be explicit")
    if cooldown <= timedelta(0) or cooldown > timedelta(days=1):
        raise QueueError("cooldown must be between zero and one day")
    current_time = now or datetime.now(timezone.utc)
    if current_time.tzinfo is None:
        raise QueueError("current time must include a timezone")
    artifacts = api.list_artifacts()
    _validate_inventory(artifacts)

    reports = _authenticated_by_identity(
        api,
        artifacts,
        repository=repository,
        default_branch=default_branch,
        pattern=REPORT_NAME,
        workflow=DRAIN_WORKFLOW,
        events=DRAIN_EVENTS,
        conclusions=TERMINAL_CONCLUSIONS,
    )
    attempts = _authenticated_by_identity(
        api,
        artifacts,
        repository=repository,
        default_branch=default_branch,
        pattern=ATTEMPT_NAME,
        workflow=DRAIN_WORKFLOW,
        events=DRAIN_EVENTS,
        conclusions=TERMINAL_CONCLUSIONS,
        allow_in_progress=True,
    )
    cooldowns = _authenticated_by_identity(
        api,
        artifacts,
        repository=repository,
        default_branch=default_branch,
        pattern=COOLDOWN_NAME,
        workflow=DRAIN_WORKFLOW,
        events=DRAIN_EVENTS,
        conclusions=TERMINAL_CONCLUSIONS,
        allow_in_progress=True,
    )
    pending = _authenticated_by_identity(
        api,
        artifacts,
        repository=repository,
        default_branch=default_branch,
        pattern=INPUT_NAME,
        workflow=PREPARE_WORKFLOW,
        events=PREPARE_EVENTS,
        conclusions=frozenset({"success"}),
    )
    for identity, values in tuple(pending.items()):
        if len(values) == 1:
            continue
        producer_ids = {artifact.producer_run_id for artifact, _owner in values}
        producer_attempts = [
            parse_producer_attempt(artifact.name) for artifact, _owner in values
        ]
        if (
            len(producer_ids) != 1
            or any(attempt is None for attempt in producer_attempts)
            or len(set(producer_attempts)) != len(producer_attempts)
        ):
            raise QueueError(
                "an authenticated queue identity has ambiguous input artifacts"
            )
        pending[identity] = [
            max(
                values,
                key=lambda value: parse_producer_attempt(value[0].name) or 0,
            )
        ]
    _validate_authenticated_inventory(
        api,
        artifacts,
        repository=repository,
        default_branch=default_branch,
        groups=(reports, attempts, cooldowns, pending),
    )
    stale_sources: set[ReviewIdentity] = set()
    for identity in pending:
        historical_source_run = api.get_run_attempt(
            identity.source_run_id, identity.source_run_attempt
        )
        if not authenticate_source_identity(
            historical_source_run, repository=repository, identity=identity
        ):
            raise QueueError("visual queue source run identity is not exact")
        current_source_run = api.get_run(identity.source_run_id)
        current_run_id = (
            current_source_run.get("id") if isinstance(current_source_run, dict) else None
        )
        current_attempt = (
            current_source_run.get("run_attempt")
            if isinstance(current_source_run, dict)
            else None
        )
        if (
            type(current_run_id) is int
            and current_run_id == identity.source_run_id
            and type(current_attempt) is int
            and current_attempt > 0
            and current_attempt != identity.source_run_attempt
        ):
            stale_sources.add(identity)
            continue
        if not authenticate_source_identity(
            current_source_run, repository=repository, identity=identity
        ):
            raise QueueError("visual queue source run attempt is no longer current")
    attempt_state: dict[
        ReviewIdentity, tuple[dict[int, Artifact], bool]
    ] = {}
    for identity, values in attempts.items():
        ordinals = [parse_attempt_ordinal(artifact.name) for artifact, _ in values]
        if any(ordinal is None for ordinal in ordinals):
            raise QueueError("authenticated attempt has no exact ordinal")
        exact_ordinals = [ordinal for ordinal in ordinals if ordinal is not None]
        if len(exact_ordinals) != len(set(exact_ordinals)):
            raise QueueError("authenticated attempt ordinal is duplicated")
        if sorted(exact_ordinals) not in ([1], [1, 2]):
            raise QueueError("authenticated attempt ordinals are discontinuous")
        by_ordinal = {
            ordinal: artifact
            for ordinal, (artifact, _) in zip(exact_ordinals, values, strict=True)
        }
        attempt_state[identity] = (
            by_ordinal,
            any(owner.active for _, owner in values),
        )

    cooldown_state: dict[ReviewIdentity, dict[int, tuple[Artifact, int]]] = {}
    for identity, values in cooldowns.items():
        parsed = [parse_cooldown(artifact.name) for artifact, _ in values]
        if any(item is None for item in parsed):
            raise QueueError("authenticated cooldown has no exact identity")
        exact = [item for item in parsed if item is not None]
        if any(item[0] != identity for item in exact):
            raise QueueError("authenticated cooldown identity is inconsistent")
        ordinals = [item[1] for item in exact]
        if len(ordinals) != len(set(ordinals)):
            raise QueueError("authenticated cooldown ordinal is duplicated")
        if sorted(ordinals) not in ([1], [1, 2]):
            raise QueueError("authenticated cooldown ordinals are discontinuous")
        attempt_ordinals = set(attempt_state.get(identity, ({}, False))[0])
        if not set(ordinals).issubset(attempt_ordinals):
            raise QueueError("authenticated cooldown ordinal has no matching attempt")
        cooldown_state[identity] = {
            item[1]: (artifact, item[2])
            for item, (artifact, _) in zip(exact, values, strict=True)
        }
    candidates: list[Selection] = []
    for identity, values in pending.items():
        artifact, producer_owner = values[0]
        if identity in reports:
            continue
        if artifact.size_in_bytes > MAX_INPUT_BYTES:
            raise QueueError("authenticated visual input exceeds the size bound")
        if identity in stale_sources:
            candidates.append(
                Selection(
                    identity,
                    artifact,
                    producer_owner.run_attempt,
                    "stale_source",
                    0,
                    None,
                )
            )
            continue
        attempt_artifacts, active_attempt = attempt_state.get(identity, ({}, False))
        completed_attempts = len(attempt_artifacts)
        if active_attempt:
            continue
        if attempt_artifacts:
            latest_ordinal = max(attempt_artifacts)
            cooldown_record = cooldown_state.get(identity, {}).get(latest_ordinal)
            if cooldown_record is None:
                defer_until = (
                    attempt_artifacts[latest_ordinal].created_at + cooldown
                )
            else:
                cooldown_artifact, delay_seconds = cooldown_record
                defer_until = cooldown_artifact.created_at + max(
                    cooldown, timedelta(seconds=delay_seconds)
                )
            if defer_until > current_time:
                continue
        if completed_attempts >= MAX_DRAIN_ATTEMPTS:
            candidates.append(
                Selection(
                    identity,
                    artifact,
                    producer_owner.run_attempt,
                    "attempts_exhausted",
                    completed_attempts,
                    None,
                )
            )
            continue
        candidates.append(
            Selection(
                identity,
                artifact,
                producer_owner.run_attempt,
                "eligible",
                completed_attempts,
                completed_attempts + 1,
            )
        )
    if not candidates:
        return None
    return min(candidates, key=lambda item: item.artifact.order)


def cleanup_target_for_outcome(
    selection: Selection, outcome: str
) -> CleanupTarget | None:
    """Retain all retryable/ambiguous failures and exhausted manual-review items."""

    if outcome in RETAINING_OUTCOMES:
        return None
    if outcome not in TERMINAL_OUTCOMES:
        raise QueueError(f"unknown queue outcome {outcome!r}")
    return CleanupTarget.from_artifact(selection.artifact)


def plan_exact_cleanup(
    target: CleanupTarget, *, status_code: int, metadata: Any
) -> CleanupPlan:
    """Authenticate an exact artifact immediately before deletion.

    A 404 is a successful idempotent no-op.  Every other non-200 response and
    every metadata mismatch retains the queue item by raising before deletion.
    """

    if status_code == 404:
        if metadata is not None:
            raise QueueError("a 404 cleanup response must not include metadata")
        return CleanupPlan(target.artifact_id, delete=False, already_absent=True)
    if status_code != 200:
        raise QueueError("cleanup metadata lookup did not return 200 or 404")
    observed = parse_artifact(metadata)
    expected = Artifact(
        artifact_id=target.artifact_id,
        name=target.name,
        size_in_bytes=target.size_in_bytes,
        digest=target.digest,
        expired=False,
        created_at=target.created_at,
        producer_run_id=target.producer_run_id,
        producer_head_branch=target.producer_head_branch,
        producer_head_sha=target.producer_head_sha,
    )
    if observed != expected:
        raise QueueError("cleanup artifact metadata no longer matches the exact target")
    return CleanupPlan(target.artifact_id, delete=True, already_absent=False)


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        parsed = urllib.parse.urlsplit(api_url)
        if (
            api_url.rstrip("/") != "https://api.github.com"
            or parsed.scheme != "https"
            or parsed.netloc != "api.github.com"
            or parsed.path not in {"", "/"}
            or parsed.query
            or parsed.fragment
        ):
            raise QueueError("GitHub API URL must be the canonical GitHub HTTPS API")
        if (
            not isinstance(token, str)
            or not token
            or any(ord(character) < 32 for character in token)
        ):
            raise QueueError("GitHub API token is empty or contains control characters")
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            _NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )
        self._runs: dict[int, dict[str, Any]] = {}
        self._run_attempts: dict[tuple[int, int], dict[str, Any]] = {}

    def _request(self, path: str) -> Any:
        request = urllib.request.Request(
            f"{self.api_url}{path}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "BlockPops-visual-review-queue/1",
            },
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                payload = response.read(MAX_API_BYTES + 1)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as exc:
            raise QueueError("GitHub API request failed") from exc
        if len(payload) > MAX_API_BYTES:
            raise QueueError("GitHub API response exceeds the byte bound")
        try:
            return json.loads(
                payload,
                object_pairs_hook=_reject_duplicate_keys,
                parse_constant=_reject_nonfinite,
            )
        except (UnicodeError, json.JSONDecodeError, QueueError) as exc:
            raise QueueError("GitHub API returned invalid JSON") from exc

    def list_artifacts(self) -> list[Artifact]:
        artifacts: list[Artifact] = []
        for page in range(1, 101):
            query = urllib.parse.urlencode({"per_page": 100, "page": page})
            payload = self._request(
                f"/repos/{self.repository}/actions/artifacts?{query}"
            )
            if not isinstance(payload, dict) or not isinstance(
                payload.get("artifacts"), list
            ):
                raise QueueError("artifact inventory response is invalid")
            batch = payload["artifacts"]
            for item in batch:
                if not isinstance(item, dict):
                    raise QueueError("artifact inventory contains a non-object")
                name = item.get("name")
                if isinstance(name, str) and name.startswith("visual-review"):
                    artifacts.append(parse_artifact(item))
            if len(artifacts) > MAX_ARTIFACTS:
                raise QueueError(f"artifact inventory exceeds {MAX_ARTIFACTS}")
            if len(batch) < 100:
                return artifacts
        raise QueueError(f"artifact inventory exceeds {MAX_ARTIFACTS}")

    def get_run(self, run_id: int) -> dict[str, Any]:
        cached = self._runs.get(run_id)
        if cached is not None:
            return cached
        payload = self._request(f"/repos/{self.repository}/actions/runs/{run_id}")
        if not isinstance(payload, dict):
            raise QueueError("workflow run response is invalid")
        self._runs[run_id] = payload
        return payload

    def get_run_attempt(self, run_id: int, run_attempt: int) -> dict[str, Any]:
        key = (run_id, run_attempt)
        cached = self._run_attempts.get(key)
        if cached is not None:
            return cached
        payload = self._request(
            f"/repos/{self.repository}/actions/runs/{run_id}/attempts/{run_attempt}"
        )
        if not isinstance(payload, dict):
            raise QueueError("workflow run attempt response is invalid")
        self._run_attempts[key] = payload
        return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--default-branch", required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--cooldown-minutes", type=int, default=DEFAULT_COOLDOWN_MINUTES)
    args = parser.parse_args(argv)
    try:
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise QueueError("GH_TOKEN is required")
        selected = select_pending(
            GitHubApi(
                repository=args.repository,
                token=token,
                api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
            ),
            repository=args.repository,
            default_branch=args.default_branch,
            cooldown=timedelta(minutes=args.cooldown_minutes),
        )
        with args.github_output.open("a", encoding="utf-8") as output:
            if selected is None:
                output.write("eligible=false\n")
                return 0
            output.write("eligible=true\n")
            output.write(f"artifact_id={selected.artifact.artifact_id}\n")
            output.write(f"artifact_name={selected.artifact.name}\n")
            output.write(
                f"artifact_digest={selected.artifact.digest.removeprefix('sha256:')}\n"
            )
            output.write(f"artifact_size={selected.artifact.size_in_bytes}\n")
            output.write(f"producer_run_id={selected.artifact.producer_run_id}\n")
            output.write(f"producer_run_attempt={selected.producer_run_attempt}\n")
            output.write(f"implementation_sha={selected.artifact.producer_head_sha}\n")
            output.write(f"source_run_id={selected.identity.source_run_id}\n")
            output.write(f"source_run_attempt={selected.identity.source_run_attempt}\n")
            output.write(f"tested_sha={selected.identity.tested_sha}\n")
            output.write(f"queue_state={selected.queue_state}\n")
            output.write(f"completed_attempts={selected.completed_attempts}\n")
            if selected.next_attempt_ordinal is not None:
                output.write(
                    f"next_attempt_ordinal={selected.next_attempt_ordinal}\n"
                )
        return 0
    except (OSError, QueueError) as exc:
        print(f"Visual review queue error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
