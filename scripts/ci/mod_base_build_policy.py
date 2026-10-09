"""Block Pops' policy suite for the mod-base ``policy`` hook.

It is the candidate step of ``.github/workflows/build-gate.yml`` before the build, in the same
order and with the same commands, run in the candidate's private clone of the tested commit
(``mod_base_build_dispatch.Candidate``): the hash-locked Python requirements, the release matrix,
the loader bootstrap, the dependency policy, a compile of every Python source and both unit suites
through ``scripts/ci/parallel_unittest.py``, which fails unless every discovered test ran and
passed. The runner-side checks of the same job that read only candidate bytes, the wrapper JAR
against its committed checksum and the wrapper properties against the committed distribution
checksum, run first. The Gradle wrapper-validation action stays with the native gate.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

WRAPPER = Path("gradle") / "wrapper"
DISTRIBUTION_CHECKSUM = "gradle-9.7.1-bin.zip.sha256"


class PolicyError(ValueError):
    """A policy check failed."""


def check_wrapper(work: Path) -> None:
    """``sha256sum --check --strict gradle-wrapper.jar.sha256`` and the distribution pin."""

    wrapper = work / WRAPPER
    lines = [line for line in (wrapper / "gradle-wrapper.jar.sha256").read_text("utf-8").splitlines() if line.strip()]
    if not lines:
        raise PolicyError("gradle-wrapper.jar.sha256 lists nothing")
    for line in lines:
        digest, name = line.split(maxsplit=1)
        path = wrapper / name.lstrip("*")
        if path.is_symlink() or not path.is_file() or path.parent != wrapper:
            raise PolicyError(f"{name} is not a wrapper file")
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise PolicyError(f"gradle/wrapper/{name} differs from its checksum")
    distribution = (wrapper / DISTRIBUTION_CHECKSUM).read_text("utf-8").strip()
    properties = (wrapper / "gradle-wrapper.properties").read_text("utf-8").splitlines()
    if f"distributionSha256Sum={distribution}" not in properties:
        raise PolicyError("gradle-wrapper.properties does not pin the checked distribution")


def run(candidate: Any) -> None:
    """Run the suite in ``candidate`` (a ``mod_base_build_dispatch.Candidate``); raise on failure."""

    candidate.clone(with_kit=True)
    check_wrapper(candidate.work)
    candidate.pip_user("scripts/pages/requirements.txt")
    candidate.native("validate the release matrix", "scripts/release/matrix.py", "--matrix",
                     "release/release-matrix.json", quiet=True)
    candidate.native("validate the loader bootstrap", "scripts/ci/loader_bootstrap.py", "--repository", ".",
                     "--head-sha", candidate.tested_sha, quiet=True)
    candidate.native("check the dependency policy", "scripts/ci/dependency_policy.py", "--metadata",
                     "gradle/verification-metadata.xml", quiet=True)
    candidate.native("compile every Python source", "-m", "compileall", "-q", "e2e", "scripts", "tests")
    candidate.native("run both unit suites", "scripts/ci/parallel_unittest.py", "-v", "-t", ".",
                     "scripts/ci/tests", "tests")
