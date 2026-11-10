"""Trusted workflow_run reporter: API-owned PR identity, bounded data, one bot comment."""
import argparse
import io
from enum import Enum
import json
import os
import re
import urllib.parse
import urllib.request
import zipfile
import zlib

from coverage_report import PLATFORMS, decode, passes, table, validate

MARKER = "<!-- graphfold-unit-coverage:v1 -->"
MAX_ARCHIVE = 128 * 1024
MAX_JSON = 32 * 1024


class CoverageStatus(str, Enum):
    PASSED = "Passed"
    FAILED = "Failed"
    CANCELLED = "Cancelled"
    SKIPPED = "Skipped"
    TIMED_OUT = "Timed out"
    INCOMPLETE = "Incomplete"
    MISSING_OR_INCOMPLETE = "Missing or incomplete"
    FAILED_OR_INCOMPLETE = "Failed or incomplete"


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        target = urllib.parse.urlparse(url)
        if target.scheme != "https":
            raise ValueError("Insecure artifact redirect")
        redirected = super().redirect_request(request, fp, code, message, headers, url)
        if target.netloc != urllib.parse.urlparse(request.full_url).netloc:
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Invalid repository")
        self.repository = repository
        self.token = token
        self.opener = urllib.request.build_opener(SafeRedirect())

    def request(self, path, method="GET", body=None, binary=False):
        if not path.startswith("/") or ".." in path:
            raise ValueError("Invalid API path")
        request = urllib.request.Request("https://api.github.com/repos/" + self.repository + path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"})
        with self.opener.open(request, timeout=30) as response:
            data = response.read(MAX_ARCHIVE + 1) if binary else response.read()
        if binary:
            if len(data) > MAX_ARCHIVE:
                raise ValueError("Coverage archive too large")
            return data
        return json.loads(data) if data else None

    def pages(self, path, key=None):
        separator = "&" if "?" in path else "?"
        for page in range(1, 1001):
            response = self.request(f"{path}{separator}per_page=100&page={page}")
            values = response[key] if key else response
            if not isinstance(values, list):
                raise ValueError("Invalid API list")
            yield from values
            if len(values) < 100:
                return
        raise ValueError("API pagination exceeded limit")


def run_identity(api, run_id, attempt):
    run = api.request(f"/actions/runs/{run_id}")
    if (run["id"] != run_id or run["run_attempt"] != attempt or run["event"] != "pull_request" or
            run["status"] != "completed" or run["name"] != "Build" or
            run["path"].split("@")[0] != ".github/workflows/build.yml"):
        return None
    if not re.fullmatch(r"[0-9a-f]{40}", run["head_sha"]):
        raise ValueError("Invalid run SHA")
    if type(run["workflow_id"]) is not int:
        raise ValueError("Invalid workflow ID")
    return run


def matches(pr, run):
    return (pr["state"] == "open" and pr["head"]["sha"] == run["head_sha"] and
            pr["head"].get("repo") is not None and
            pr["head"]["repo"]["full_name"] == run["head_repository"]["full_name"])


def associated_numbers(run):
    return {pr["number"] for pr in run.get("pull_requests", [])}


def matching_prs(api, run):
    return [pr["number"] for pr in api.pages("/pulls?state=open") if matches(pr, run)]


def belongs(api, run, number):
    associated = associated_numbers(run)
    if associated:
        return number in associated
    # A fork run may omit PR associations. Ambiguous same-head PRs can have different merge bases.
    return matching_prs(api, run) == [number]


def latest(api, run, number):
    path = f"/actions/workflows/{run['workflow_id']}/runs?event=pull_request&head_sha={run['head_sha']}"
    return not any(candidate["id"] > run["id"] and
        candidate["head_repository"]["full_name"] == run["head_repository"]["full_name"] and
        belongs(api, candidate, number) for candidate in api.pages(path, "workflow_runs"))


def resolve(api, run_id, attempt):
    run = run_identity(api, run_id, attempt)
    if run is None:
        return []
    candidates = matching_prs(api, run)
    associated = associated_numbers(run)
    if associated:
        candidates = [number for number in candidates if number in associated]
    elif len(candidates) != 1:
        print("Skipped: run has no authoritative PR association and no unique matching open PR")
        return []
    return sorted(number for number in candidates if latest(api, run, number))


def read_archive(data, platform):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if len(entries) != 1 or entries[0].filename != "coverage.json" or entries[0].file_size > MAX_JSON:
            raise ValueError("Invalid coverage archive inventory")
        raw = archive.read(entries[0])
        if len(raw) > MAX_JSON:
            raise ValueError("Coverage JSON too large")
    return validate(decode(raw), platform)


def documents(api, run_id, attempt):
    artifacts = list(api.pages(f"/actions/runs/{run_id}/artifacts", "artifacts"))
    result = {}
    for platform in PLATFORMS:
        matching = [item for item in artifacts if item["name"] == f"coverage-{platform}-attempt-{attempt}" and not item["expired"]]
        if len(matching) != 1:
            continue
        artifact = matching[0]
        try:
            if type(artifact["id"]) is not int or artifact["size_in_bytes"] > MAX_ARCHIVE:
                raise ValueError("Invalid coverage artifact")
            result[platform] = read_archive(api.request(f"/actions/artifacts/{artifact['id']}/zip", binary=True), platform)
        except (ValueError, KeyError, TypeError, zipfile.BadZipFile, UnicodeError, RuntimeError, zlib.error):
            # Invalid artifacts cannot claim coverage or supply comment text.
            continue
    return result


def platform_jobs(api, run):
    jobs = list(api.pages(f"/actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", "jobs"))
    values = {}
    for platform in PLATFORMS:
        selected = [job for job in jobs if job["name"] == f"verify ({platform})"]
        if len(selected) != 1 or selected[0]["status"] != "completed":
            values[platform] = CoverageStatus.MISSING_OR_INCOMPLETE
        else:
            conclusion = selected[0]["conclusion"]
            values[platform] = {"success": CoverageStatus.PASSED, "failure": CoverageStatus.FAILED,
                "cancelled": CoverageStatus.CANCELLED, "skipped": CoverageStatus.SKIPPED,
                "timed_out": CoverageStatus.TIMED_OUT}.get(conclusion, CoverageStatus.INCOMPLETE)
    return values


def comment_body(api, run, reports, jobs):
    covered = set(reports) == set(PLATFORMS) and all(passes(report) for report in reports.values())
    status = CoverageStatus.PASSED if covered and run["conclusion"] == "success" and all(
        jobs.get(platform) == CoverageStatus.PASSED for platform in PLATFORMS) else CoverageStatus.FAILED_OR_INCOMPLETE
    url = f"https://github.com/{api.repository}/actions/runs/{run['id']}"
    return (f"{MARKER}\n### Unit test coverage: {status.value}\n\n"
        f"Commit `{run['head_sha']}` · [Build run {run['id']}, attempt {run['run_attempt']}]({url})\n\n"
        "Required for every module on both platforms: **100% line and 100% branch coverage**. "
        "A pass requires zero missed lines/branches and nonzero measured totals.\n\n" +
        "| Platform | Verification |\n| --- | --- |\n" +
        "\n".join(f"| {platform} | {jobs.get(platform, CoverageStatus.MISSING_OR_INCOMPLETE).value} |" for platform in PLATFORMS) +
        "\n\n" + table(reports) +
        "\n\nCoverage reports describe this run; missing or invalid reports never count as 100%. "
        "The required `run-unit-test` check also requires all tests and verification steps to succeed.")


def upsert(api, number, body):
    owned = [item for item in api.pages(f"/issues/{number}/comments")
        if item.get("user", {}).get("login") == "github-actions[bot]" and
        item.get("user", {}).get("type") == "Bot" and (item.get("body") or "").startswith(MARKER + "\n")]
    owned.sort(key=lambda item: item["id"])
    if owned:
        api.request(f"/issues/comments/{owned[0]['id']}", "PATCH", {"body": body})
        for duplicate in owned[1:]:
            api.request(f"/issues/comments/{duplicate['id']}", "DELETE")
    else:
        api.request(f"/issues/{number}/comments", "POST", {"body": body})


def publish(api, run_id, attempt, number):
    run = run_identity(api, run_id, attempt)
    if run is None or not matches(api.request(f"/pulls/{number}"), run) or not belongs(api, run, number) or not latest(api, run, number):
        return False
    reports = documents(api, run_id, attempt)
    jobs = platform_jobs(api, run)
    # Recheck after artifact I/O, inside the workflow's per-PR concurrency group.
    current = run_identity(api, run_id, attempt)
    if current is None or not matches(api.request(f"/pulls/{number}"), current) or not belongs(api, current, number) or not latest(api, current, number):
        return False
    upsert(api, number, comment_body(api, current, reports, jobs))
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("resolve", "publish"))
    parser.add_argument("--run", type=int, required=True)
    parser.add_argument("--attempt", type=int, required=True)
    parser.add_argument("--pr", type=int)
    args = parser.parse_args()
    api = GitHub(os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"])
    if args.mode == "resolve":
        prs = resolve(api, args.run, args.attempt)
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("prs=" + json.dumps(prs) + "\n")
    else:
        if args.pr is None or args.pr <= 0:
            parser.error("publish requires a positive --pr")
        print("Coverage comment updated" if publish(api, args.run, args.attempt, args.pr) else "Stale or unrelated run skipped")
