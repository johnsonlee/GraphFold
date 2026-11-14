import copy
import io
import json
import unittest
import urllib.request
import zipfile
from unittest.mock import Mock

from coverage_comment import (CoverageStatus, GitHub, MARKER, MAX_ARCHIVE, MAX_JSON, SafeRedirect, comment_body,
                              documents, latest, publish, read_archive, resolve, upsert)
from coverage_report import MODULES, PLATFORMS
from test_coverage_report import report


def zipped(document, name="coverage.json"):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr(name, json.dumps(document) if isinstance(document, dict) else document)
    return stream.getvalue()


class FakeAPI:
    repository = "owner/graphfold"

    def __init__(self):
        self.run = {"id": 100, "run_attempt": 1, "event": "pull_request", "status": "completed", "name": "Build",
            "path": ".github/workflows/build.yml", "head_sha": "a" * 40, "workflow_id": 1,
            "head_repository": {"full_name": "fork/graphfold"}, "conclusion": "success"}
        self.pr = {"number": 7, "state": "open", "head": {"sha": "a" * 40, "repo": {"full_name": "fork/graphfold"}}}
        self.runs = [self.run]
        self.prs = [self.pr]
        self.comments = []
        self.artifacts = [{"id": index, "name": "coverage-" + platform + "-attempt-1", "expired": False, "size_in_bytes": 1000}
                          for index, platform in enumerate(PLATFORMS, 1)]
        self.archives = {index: zipped(report(platform)) for index, platform in enumerate(PLATFORMS, 1)}
        self.calls = []
        self.pr_reads = 0
        self.change_head_after_download = False

    def request(self, path, method="GET", body=None, binary=False):
        self.calls.append((path, method, body))
        if method != "GET":
            return None
        if path == "/actions/runs/100":
            return copy.deepcopy(self.run)
        if path == "/pulls/7":
            self.pr_reads += 1
            value = copy.deepcopy(self.pr)
            if self.change_head_after_download and self.pr_reads > 1:
                value["head"]["sha"] = "b" * 40
            return value
        if binary:
            return self.archives[int(path.split("/")[-2])]
        raise AssertionError(path)

    def pages(self, path, key=None):
        if path == "/pulls?state=open":
            return iter(self.prs)
        if path.startswith("/actions/workflows/1/runs?"):
            return iter(self.runs)
        if path == "/actions/runs/100/artifacts":
            return iter(self.artifacts)
        if path == "/actions/runs/100/attempts/1/jobs":
            return iter({"name": f"verify ({platform})", "status": "completed", "conclusion": "success"} for platform in PLATFORMS)
        if path == "/issues/7/comments":
            return iter(self.comments)
        raise AssertionError(path)

    def writes(self):
        return [call for call in self.calls if call[1] != "GET"]


class CoverageCommentTest(unittest.TestCase):
    def test_fork_pr_is_resolved_from_api_identity_without_artifact_claims(self):
        api = FakeAPI()
        self.assertEqual([7], resolve(api, 100, 1))
        api.pr["head"]["repo"]["full_name"] = "different/graphfold"
        self.assertEqual([], resolve(api, 100, 1))
        self.assertFalse(any("artifact" in path for path, _, _ in api.calls))

    def test_same_head_different_base_prs_require_authoritative_run_association(self):
        api = FakeAPI()
        api.prs.append({**copy.deepcopy(api.pr), "number": 8})
        self.assertEqual([], resolve(api, 100, 1))
        self.assertFalse(publish(api, 100, 1, 7))
        self.assertEqual([], api.writes())
        api.run["pull_requests"] = [{"number": 7}]
        self.assertEqual([7], resolve(api, 100, 1))
        self.assertTrue(publish(api, 100, 1, 7))
        api.run["pull_requests"] = [{"number": 8}]
        self.assertEqual([8], resolve(api, 100, 1))
        self.assertFalse(publish(api, 100, 1, 7))

    def test_newer_run_for_another_same_head_pr_does_not_suppress_this_pr(self):
        api = FakeAPI()
        api.prs.append({**copy.deepcopy(api.pr), "number": 8})
        api.run["pull_requests"] = [{"number": 7}]
        candidate = {**api.run, "id": 101, "pull_requests": [{"number": 8}]}
        api.runs.append(candidate)
        self.assertTrue(latest(api, api.run, 7))
        self.assertEqual([7], resolve(api, 100, 1))
        candidate["pull_requests"] = [{"number": 7}]
        self.assertFalse(latest(api, api.run, 7))
        self.assertEqual([], resolve(api, 100, 1))

    def test_stale_head_rerun_attempt_newer_run_and_other_workflow_are_ignored(self):
        mutations = [lambda a: a.pr["head"].update(sha="b" * 40), lambda a: a.run.update(run_attempt=2),
            lambda a: a.runs.append({**a.run, "id": 101}), lambda a: a.run.update(event="push"),
            lambda a: a.run.update(path=".github/workflows/untrusted.yml"), lambda a: a.pr.update(state="closed")]
        for mutate in mutations:
            api = FakeAPI()
            mutate(api)
            self.assertFalse(publish(api, 100, 1, 7))
            self.assertEqual([], api.writes())

    def test_head_is_rechecked_after_artifact_download(self):
        api = FakeAPI()
        api.change_head_after_download = True
        self.assertFalse(publish(api, 100, 1, 7))
        self.assertEqual([], api.writes())

    def test_first_report_creates_one_comment_with_all_modules_and_platforms(self):
        api = FakeAPI()
        self.assertTrue(publish(api, 100, 1, 7))
        self.assertEqual(1, len(api.writes()))
        path, method, payload = api.writes()[0]
        self.assertEqual(("/issues/7/comments", "POST"), (path, method))
        self.assertTrue(payload["body"].startswith(MARKER + "\n"))
        self.assertIn("Unit test coverage: Passed", payload["body"])
        self.assertEqual(len(MODULES) + 1, payload["body"].count("ubuntu-latest"))
        self.assertEqual(len(MODULES) + 1, payload["body"].count("macos-latest"))
        self.assertIn("https://github.com/owner/graphfold/actions/runs/100", payload["body"])

    def test_existing_owned_comment_is_updated_and_owned_duplicates_deleted(self):
        api = FakeAPI()
        bot = {"login": "github-actions[bot]", "type": "Bot"}
        api.comments = [
            {"id": 9, "body": MARKER + "\nold", "user": bot},
            {"id": 8, "body": MARKER + "\nolder", "user": bot},
            {"id": 1, "body": MARKER + "\nhuman", "user": {"login": "someone", "type": "User"}},
            {"id": 2, "body": MARKER + "\nother bot", "user": {"login": "other[bot]", "type": "Bot"}},
            {"id": 3, "body": "Other workflow report", "user": bot},
        ]
        upsert(api, 7, "new body")
        self.assertEqual([("/issues/comments/8", "PATCH", {"body": "new body"}),
                          ("/issues/comments/9", "DELETE", None)], api.writes())

    def test_missing_expired_duplicate_or_invalid_platform_is_never_a_pass(self):
        variants = [lambda a: a.artifacts.pop(), lambda a: a.artifacts[1].update(expired=True),
            lambda a: a.artifacts.append(a.artifacts[1].copy()),
            lambda a: a.archives.update({2: b"not a zip"}),
            lambda a: a.artifacts[1].update(size_in_bytes=MAX_ARCHIVE + 1),
            lambda a: a.archives.update({2: zipped({**report(PLATFORMS[1]), "pr": 999})})]
        for mutate in variants:
            api = FakeAPI()
            mutate(api)
            self.assertTrue(publish(api, 100, 1, 7))
            body = api.writes()[0][2]["body"]
            self.assertIn("Failed or incomplete", body)
            self.assertIn("Missing platform report", body)
            self.assertNotIn("999", body)

    def test_artifacts_from_a_previous_attempt_are_not_reused(self):
        api = FakeAPI()
        api.artifacts[1]["name"] = "coverage-macos-latest-attempt-0"
        self.assertEqual({PLATFORMS[0]}, set(documents(api, 100, 1)))

    def test_platform_job_failure_prevents_pass_even_when_reports_are_complete(self):
        api = FakeAPI()
        for status in (CoverageStatus.FAILED, CoverageStatus.CANCELLED, CoverageStatus.MISSING_OR_INCOMPLETE):
            body = comment_body(api, api.run, {p: report(p) for p in PLATFORMS},
                {PLATFORMS[0]: CoverageStatus.PASSED, PLATFORMS[1]: status})
            self.assertIn("Failed or incomplete", body)
            self.assertIn(status.value, body)

    def test_failed_test_run_does_not_pass_even_with_full_coverage(self):
        api = FakeAPI()
        api.run["conclusion"] = "failure"
        self.assertIn("Failed or incomplete", comment_body(api, api.run, {p: report(p) for p in PLATFORMS}, {p: CoverageStatus.PASSED for p in PLATFORMS}))

    def test_archive_accepts_only_one_bounded_json_file(self):
        for data in (zipped(report(), "../coverage.json"), zipped(" " * (MAX_JSON + 1)),
                     zipped('{"schema":1,"schema":1}'), zipped(report(PLATFORMS[1]))):
            with self.assertRaises(ValueError):
                read_archive(data, PLATFORMS[0])
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("coverage.json", json.dumps(report()))
            archive.writestr("script.py", "print('untrusted')")
        with self.assertRaises(ValueError):
            read_archive(stream.getvalue(), PLATFORMS[0])
        self.assertEqual(report(), read_archive(zipped(report()), PLATFORMS[0]))

    def test_encrypted_and_unsupported_zip_payloads_produce_an_incomplete_report(self):
        for field_offset, value in ((6, 1), (8, 99)):
            api = FakeAPI()
            raw = bytearray(api.archives[2])
            local = raw.index(b"PK\x03\x04")
            central = raw.index(b"PK\x01\x02")
            raw[local + field_offset:local + field_offset + 2] = value.to_bytes(2, "little")
            raw[central + field_offset + 2:central + field_offset + 4] = value.to_bytes(2, "little")
            api.archives[2] = bytes(raw)
            self.assertTrue(publish(api, 100, 1, 7))
            self.assertIn("Failed or incomplete", api.writes()[0][2]["body"])
            self.assertIn("Missing platform report", api.writes()[0][2]["body"])

    def test_corrupt_deflate_data_produces_an_incomplete_report(self):
        api = FakeAPI()
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("coverage.json", json.dumps(report(PLATFORMS[1])))
        raw = bytearray(stream.getvalue())
        name_length = int.from_bytes(raw[26:28], "little")
        extra_length = int.from_bytes(raw[28:30], "little")
        raw[30 + name_length + extra_length] = 7  # Reserved DEFLATE block type.
        api.archives[2] = bytes(raw)
        self.assertTrue(publish(api, 100, 1, 7))
        self.assertIn("Failed or incomplete", api.writes()[0][2]["body"])

    def test_api_paginates_until_last_page(self):
        api = GitHub("owner/graphfold", "unused")
        api.request = Mock(side_effect=[list(range(100)), [100]])
        self.assertEqual(list(range(101)), list(api.pages("/issues/7/comments")))
        self.assertEqual("/issues/7/comments?per_page=100&page=2", api.request.call_args.args[0])
        api.request = Mock(side_effect=[{"artifacts": list(range(100))}, {"artifacts": []}])
        self.assertEqual(list(range(100)), list(api.pages("/actions/runs/100/artifacts", "artifacts")))

    def test_artifact_redirect_never_forwards_credentials_to_storage_host(self):
        request = urllib.request.Request("https://api.github.com/repos/owner/graphfold/actions/artifacts/1/zip",
            headers={"Authorization": "Bearer secret"})
        handler = SafeRedirect()
        redirected = handler.redirect_request(request, None, 302, "found", {}, "https://storage.example/archive.zip")
        self.assertIsNone(redirected.get_header("Authorization"))
        with self.assertRaises(ValueError):
            handler.redirect_request(request, None, 302, "found", {}, "http://storage.example/archive.zip")
