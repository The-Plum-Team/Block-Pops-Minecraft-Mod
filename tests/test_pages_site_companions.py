"""The real Pages CLI joins API ownership, real Git/ZIP bytes and compact pixels."""

import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from scripts.pages import download_artifact, evidence
from scripts.pages.select_artifact import Artifact
from tests import test_pages_site_scope as fixtures

ROOT = Path(__file__).resolve().parents[1]


class Api:
    def __init__(self, fixture): self.fixture = fixture
    def __getattr__(self, name): return getattr(self.fixture, "owner_run" if name == "run" else name)


class PagesCompanionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.ScopedSiteTests.setUpClass()
        cls.addClassCleanup(fixtures.ScopedSiteTests.doClassCleanups)

    def prepare(self, *, shared=False, mixed=False):
        f = fixtures.ScopedSiteTests(); f.setUp(); self.addCleanup(f.doCleanups); self.fixture = f
        if shared: f.configure(True)
        if mixed: f.test_preparing_shared_and_mixed_schema_gallery_keep_exact_scope_and_pixels()
        self.repo = f.current / "controller"; self.repo.mkdir()
        clean = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        isolated = patch.dict(os.environ, clean, clear=True); isolated.start(); self.addCleanup(isolated.stop)
        self.git("init", "-q"); self.git("config", "user.name", "Fixture"); self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.autocrlf", "false")
        for path in ("scripts/pages/build_site.py", "site/index.html", "site/assets/site.css", "site/assets/gallery.js"):
            target = self.repo / path; target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(ROOT / path, target)
        (self.repo / "release").mkdir(); shutil.copyfile(f.matrix_path, self.repo / "release/release-matrix.json")
        self.git("add", "."); self.git("commit", "-qm", "protected controller")
        self.head, self.tree = self.git("rev-parse", "HEAD"), self.git("rev-parse", "HEAD^{tree}")
        self.default = "master"; self.repository = "AkaNebur/BlockPops"; self.api_url = "https://api.github.com"; self.token = "fixture-token"
        self.workflow_state = "active"
        self.owner = dict(id=811, run_attempt=3, workflow_id=17, path=".github/workflows/pages.yml", head_branch="master",
            head_sha=self.head, event="schedule", status="in_progress", conclusion=None, head_repository=dict(full_name=self.repository))
        self.jobs, self.artifacts, self.archives, self.payloads, self.heads, self.downloads = [], [], {}, {}, {}, []
        for index, row in enumerate(f.rows):
            branch = row["name"]; raw = f.inputs[branch]["matrix_path"].read_bytes()
            tree, head = self.tree, self.head
            if branch != "master":
                blob = self.git("hash-object", "-w", "--stdin", data=raw)
                release = self.git("mktree", data=f"100644 blob {blob}\trelease-matrix.json\n".encode())
                tree = self.git("mktree", data=f"040000 tree {release}\trelease\n".encode())
                head = self.git("commit-tree", tree, data=b"release matrix\n")
            row.update(commit=head, tree=tree); self.heads[branch] = (head, tree)
            bundle = next(path for path in f.collected.iterdir() if json.loads((path / "manifest.json").read_bytes())["provenance"]["branch"] == branch)
            manifest = json.loads((bundle / "manifest.json").read_bytes()); provenance = manifest["provenance"]
            provenance.update(commit=head, tree=tree)
            provenance["handoff"]["controller_sha"] = self.head
            provenance["packaged"].update(commit=head, tree=tree, controller_sha=self.head)
            (bundle / "manifest.json").write_text(json.dumps(manifest))
            payloads = {"release-matrix.json": raw}
            if f.inputs[branch]["selection_path"]:
                selection = json.loads(f.inputs[branch]["selection_path"].read_bytes())
                selection.update(provenance=provenance, compact_manifest_sha256=evidence.sha256_bytes((bundle / "manifest.json").read_bytes()))
                selection["source"].update(commit=head, tree=tree)
                payloads["selection.json"] = json.dumps(selection).encode()
            identifier = 100 + index
            self.payloads[identifier] = list(payloads.items())
            self.artifacts.append(dict(id=identifier, name=f"pages-selection-{evidence.branch_token(branch)}--{head}-811-3",
                expired=False, created_at="2026-09-20T00:00:00Z", workflow_run=dict(id=811, head_branch="master", head_sha=self.head)))
            self.jobs.append(dict(id=200+index, name="Validate and compact " + branch, run_id=811, run_attempt=3,
                                  status="completed", conclusion="success"))
            self.archive(identifier)
        f.inventory_path.write_text(json.dumps(f.rows))
        spec = importlib.util.spec_from_file_location("pages_companion_fixture", self.repo / "scripts/pages/build_site.py")
        self.module = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.module)
        self.output = f.current / "authenticated-site"
        self.options = ["--pages-run-id", "811", "--pages-run-attempt", "3", "--implementation-sha", self.head, "--canonical-branch", "master"]
        self.environment = dict(GH_TOKEN=self.token, GITHUB_REPOSITORY=self.repository, GITHUB_REF="refs/heads/master",
            GITHUB_SHA=self.head, GITHUB_RUN_ID="811", GITHUB_RUN_ATTEMPT="3",
            GITHUB_WORKFLOW_REF=f"{self.repository}/.github/workflows/pages.yml@refs/heads/master")

    def git(self, *arguments, data=None):
        return subprocess.run(["git", "-C", str(self.repo), *arguments], input=data, check=True, capture_output=True).stdout.decode().strip()

    def archive(self, identifier):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name, raw in self.payloads[identifier]: archive.writestr(name, raw)
        raw = stream.getvalue(); self.archives[identifier] = raw
        next(row for row in self.artifacts if row["id"] == identifier).update(size_in_bytes=len(raw), digest="sha256:" + evidence.sha256_bytes(raw))

    def get(self, route):
        if route == "/repos/" + self.repository: return dict(full_name=self.repository, default_branch=self.default)
        if "/actions/artifacts/" in route: return copy.deepcopy(next(row for row in self.artifacts if row["id"] == int(route.rsplit("/", 1)[1])))
        tree = route.rsplit("/", 1)[1]; entries = []
        for line in self.git("ls-tree", tree).splitlines():
            metadata, name = line.split("\t"); mode, kind, sha = metadata.split()
            entries.append(dict(path=name, mode=mode, type=kind, sha=sha))
        return dict(sha=tree, truncated=False, tree=entries)

    def workflow(self, name): return dict(id=17, path=".github/workflows/pages.yml", state=self.workflow_state)
    def owner_run(self, identifier): return copy.deepcopy(self.owner)
    def run_attempt(self, identifier, attempt): return self.owner_run(identifier)
    def jobs_for_attempt(self, identifier, attempt): return copy.deepcopy(self.jobs)
    def artifacts_for_run(self, identifier): return [Artifact.parse(row) for row in self.artifacts]
    def branch_head(self, branch): return self.heads[branch]
    def open(self, request, timeout):
        self.downloads.append(request.full_url)
        return io.BytesIO(self.archives[int(request.full_url.split("/")[-2])])

    def invoke(self, options=None):
        f = self.fixture; stdout, stderr = io.StringIO(), io.StringIO()
        arguments = ["--evidence-root", str(f.collected), "--inventory", str(f.inventory_path), "--output", str(self.output),
                     "--repository", self.repository, *(self.options if options is None else options)]
        with patch.dict(os.environ, self.environment), patch.object(self.module, "GitHubApi", return_value=Api(self)) as api, \
             patch.object(download_artifact.urllib.request, "build_opener", return_value=self), redirect_stdout(stdout), redirect_stderr(stderr):
            code = self.module.main(arguments)
        self.assertEqual("https://api.github.com", api.call_args.kwargs["api_url"])
        return code, stderr.getvalue()

    def rejected(self):
        code, detail = self.invoke(); self.assertEqual(2, code, detail)
        self.assertFalse(self.output.exists()); self.assertFalse(list(self.output.parent.glob(".authenticated-site.building-*")))

    def test_real_cli_preparing_full_and_mixed_schema_transport_pixels_and_partial_scope(self):
        for options in ({}, {"shared": True}, {"mixed": True}):
            self.prepare(**options)
            self.assertEqual((0, ""), self.invoke())
            data = json.loads((self.output / "gallery-data.json").read_bytes())
            self.assertEqual(len(self.fixture.rows), len(data["releases"]))
            self.assertEqual(not options.get("shared", False), next(row for row in data["releases"] if row["branch"] == "master")["aggregate_scope"]["partial"])
            for frame in data["frames"]:
                self.assertEqual(frame["published_sha256"], evidence.sha256_bytes((self.output / frame["image"]).read_bytes()))
            self.assertEqual(len(self.artifacts), len(self.downloads))

    def test_owner_invocation_jobs_and_companion_identity_cannot_be_substituted(self):
        self.prepare()
        for target, key, bad in [(self.owner, "head_sha", "a"*40), (self.owner, "run_attempt", 2),
                (self.owner, "workflow_id", 17.0), (self.owner, "status", "completed"), (self.owner, "event", "pull_request"),
                (self.jobs[0], "conclusion", "failure"), (self.jobs[0], "run_attempt", True), (self.jobs[0], "name", "other"),
                (self.artifacts[0], "expired", True), (self.artifacts[0], "name", "old-attempt"),
                (self.environment, "GITHUB_SHA", "a"*40)]:
            before = target[key]; target[key] = bad
            with self.subTest(key=key, bad=bad): self.rejected()
            target[key] = before
        self.jobs.append(copy.deepcopy(self.jobs[0])); self.rejected(); self.jobs.pop()
        self.artifacts.append(copy.deepcopy(self.artifacts[0])); self.rejected(); self.artifacts.pop()
        self.default = "other"; self.rejected(); self.default = "master"
        self.workflow_state = "disabled"; self.rejected(); self.workflow_state = "active"
        self.api_url = "https://untrusted.invalid"; self.rejected(); self.api_url = "https://api.github.com"
        with patch.object(self.module, "MAX_SITE_BYTES", 4*1024*1024-1): self.rejected()
        self.assertEqual(2, self.invoke(options=self.options[:2])[0]); self.assertEqual([], self.downloads)

    def test_exact_archive_matrix_tree_and_selection_inputs_reject_invalid_bytes(self):
        self.prepare(); original = copy.deepcopy(self.payloads[100]); record = self.artifacts[0]
        for key, bad in (("digest", "sha256:"+"0"*64), ("size_in_bytes", record["size_in_bytes"]+1)):
            before = record[key]; record[key] = bad; self.rejected(); record[key] = before
        link = zipfile.ZipInfo("selection.json"); link.create_system = 3; link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for payloads in (original+[("extra", b"x")], [original[0], (link, b"target")],
                [original[0], ("../escape", b"x")], [original[0]],
                [("release-matrix.json", original[0][1]+b" "), original[1]],
                [original[0], ("selection.json", b"{}")]):
            self.payloads[100] = payloads; self.archive(100); self.rejected()
        self.payloads[100] = original; self.archive(100)
        rows = self.fixture.rows; rows[0]["matrix_blob"] = "a"*40
        self.fixture.inventory_path.write_text(json.dumps(rows)); self.rejected()

    def test_final_api_source_and_companion_changes_fail_before_site_publication(self):
        self.prepare(); write = self.module.write_new; captured = []; download = self.module.download_evidence_archive
        source = self.repo / "scripts/pages/build_site.py"; raw = source.read_bytes()
        def downloaded(**arguments):
            result = download(**arguments); captured.append(arguments["output"]); return result
        for mutation in ("owner", "source", "companion", "default"):
            def written(descriptor, relative, data):
                write(descriptor, relative, data)
                if relative == "gallery-data.json":
                    if mutation == "owner": self.owner["run_attempt"] = 4
                    elif mutation == "source": source.write_bytes(raw+b"\n# drift\n")
                    elif mutation == "default": self.default = "renamed"
                    else: (captured[-1] / "selection.json").write_bytes(b"{}")
            with patch.object(self.module, "write_new", side_effect=written), patch.object(self.module, "download_evidence_archive", side_effect=downloaded):
                with self.subTest(mutation=mutation): self.rejected()
            self.owner["run_attempt"] = 3; self.default = "master"; source.write_bytes(raw)

    def test_all_companion_seals_remain_live_until_the_last_companion_is_read(self):
        self.prepare(mixed=True); seal = self.module._seal_output
        for mutation in ("bytes", "directory"):
            roots = []
            def sealed(descriptor, expected, **kwargs):
                result = seal(descriptor, expected, **kwargs)
                if "release-matrix.json" in expected:
                    roots.append(descriptor)
                    if len(roots) == 2:
                        if mutation == "bytes":
                            target = os.open("release-matrix.json", os.O_WRONLY, dir_fd=roots[0])
                            try: os.write(target, b"x")
                            finally: os.close(target)
                        else:
                            parent = os.open("..", os.O_RDONLY | os.O_DIRECTORY, dir_fd=roots[0])
                            token = evidence.branch_token("master")
                            try:
                                os.rename(token, "held", src_dir_fd=parent, dst_dir_fd=parent)
                                os.mkdir(token, dir_fd=parent)
                            finally: os.close(parent)
                return result
            with patch.object(self.module, "_seal_output", side_effect=sealed): self.rejected()
            self.assertEqual(2, len(roots))

    def test_canonical_tree_must_equal_the_local_implementation_before_download(self):
        self.prepare()
        release = next(line for line in self.git("ls-tree", self.tree).splitlines() if line.endswith("\trelease"))
        different = self.git("mktree", data=(release+"\n").encode())
        self.heads["master"] = (self.head, different); self.fixture.rows[0]["tree"] = different
        self.fixture.inventory_path.write_text(json.dumps(self.fixture.rows))
        self.rejected(); self.assertEqual([], self.downloads)
