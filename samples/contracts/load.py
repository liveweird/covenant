#!/usr/bin/env python3
"""Load the reusable contract samples through Covenant's public HTTP API."""

from __future__ import annotations

import argparse
import getpass
import ipaddress
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


DEFAULT_BASE_URL = "http://localhost:8082"
DEFAULT_EMAIL = "admin@covenant.local"
DEFAULT_PASSWORD_ENV = "COVENANT_PASSWORD"
API_TITLE = "covenant-api"
SAMPLE_MARKER = "Reusable Covenant sample data (samples/contracts)"
DOMAIN_NAME = "Sample - Commerce"
TEAM_NAME = "Sample - Commerce team"
SYSTEM_NAMES = {"Orders": "Sample - Orders", "Analytics": "Sample - Analytics"}
PAGE_SIZE = 100
SEMVER = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


class SampleError(RuntimeError):
    """A safe, user-actionable loader failure."""


class NoRedirects(HTTPRedirectHandler):
    """Never forward an authorization header or credentials across redirects."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def validate_base_url(value: str, allow_remote: bool = False) -> str:
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise SampleError("--base-url must be an absolute http(s) URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise SampleError("--base-url must not contain credentials, a query, or a fragment")
    if parsed.path not in {"", "/"}:
        raise SampleError("--base-url must not contain a path")
    hostname = parsed.hostname.rstrip(".").lower()
    is_loopback = hostname == "localhost"
    try:
        is_loopback = is_loopback or ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        pass
    if not is_loopback and not allow_remote:
        raise SampleError("remote targets require the deliberate --allow-remote flag")
    if not is_loopback and parsed.scheme != "https":
        raise SampleError("remote targets must use HTTPS")
    return value.rstrip("/")


class ApiClient:
    def __init__(self, base_url: str, *, allow_remote: bool = False, timeout: float = 30.0, opener=None):
        self.base_url = validate_base_url(base_url, allow_remote)
        self.timeout = timeout
        self.opener = opener or build_opener(NoRedirects())
        self.token: str | None = None

    def _open(self, method: str, path: str, payload: Any = None, accept: str = "application/json") -> bytes:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Accept": accept}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                return response.read()
        except HTTPError as exc:
            detail = ""
            try:
                problem = json.loads(exc.read().decode("utf-8", "replace"))
                detail = problem.get("detail") or problem.get("title") or ""
            except (ValueError, AttributeError):
                pass
            suffix = f": {str(detail)[:300]}" if detail else ""
            raise SampleError(f"{method} {path} returned HTTP {exc.code}{suffix}") from exc
        except URLError as exc:
            raise SampleError(f"could not reach {self.base_url}: {exc.reason}") from exc

    def json(self, method: str, path: str, payload: Any = None) -> dict[str, Any]:
        raw = self._open(method, path, payload)
        try:
            value = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise SampleError(f"{method} {path} returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise SampleError(f"{method} {path} returned an unexpected JSON shape")
        return value

    def text(self, path: str, accept: str = "text/plain") -> str:
        return self._open("GET", path, accept=accept).decode("utf-8")

    def confirm_identity(self) -> None:
        spec = self.text("/openapi/documentation.yaml", "application/yaml")
        match = re.search(r"(?m)^\s{2}title:\s*['\"]?([^'\"\r\n]+)", spec)
        if not match or match.group(1).strip() != API_TITLE:
            raise SampleError(f"target did not identify itself as {API_TITLE!r}; refusing to send credentials")

    def login(self, email: str, password: str) -> dict[str, Any]:
        response = self.json("POST", "/api/v1/login", {"email": email, "password": password})
        if response.get("mfaRequired") or "challengeId" in response:
            raise SampleError("this account requires emailed MFA; use an account without MFA for the sample loader")
        token = response.get("token")
        if not isinstance(token, str) or not token:
            raise SampleError("login succeeded without an access token")
        self.token = token
        return response

    def list_all(self, path: str, params: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        page = 1
        items: list[dict[str, Any]] = []
        while True:
            query = dict(params or {})
            query.update(page=page, pageSize=PAGE_SIZE)
            response = self.json("GET", path + "?" + urlencode(query))
            batch = response.get("items")
            total = response.get("total")
            if not isinstance(batch, list) or not isinstance(total, int):
                raise SampleError(f"GET {path} returned an invalid page")
            items.extend(batch)
            if len(items) >= total:
                return items
            if not batch:
                raise SampleError(f"GET {path} returned an empty page before total={total}")
            page += 1


@dataclass(frozen=True)
class Sample:
    key: str
    name: str
    type: str
    version: str
    file: str
    expectation: str
    description: str
    system: str
    content: str

    @property
    def managed_description(self) -> str:
        return f"{SAMPLE_MARKER} — {self.description}"


def load_manifest(root: Path) -> list[Sample]:
    manifest_path = root / "manifest.json"
    try:
        entries = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SampleError(f"could not read {manifest_path}: {exc}") from exc
    if not isinstance(entries, list) or not entries:
        raise SampleError("manifest.json must contain a non-empty array")
    required = {"key", "name", "type", "version", "file", "expectation", "description", "system"}
    samples: list[Sample] = []
    keys: set[str] = set()
    identities: set[tuple[str, str, str]] = set()
    specs_root = (root / "specs").resolve()
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict) or set(entry) != required or not all(isinstance(entry[k], str) for k in required):
            raise SampleError(f"manifest entry {index + 1} must contain exactly the eight documented string fields")
        if entry["key"] in keys:
            raise SampleError(f"duplicate manifest key {entry['key']!r}")
        if not entry["key"]:
            raise SampleError(f"manifest entry {index + 1} has an empty key")
        if not entry["name"].startswith("Sample - ") or len(entry["name"]) > 100:
            raise SampleError(f"sample {entry['key']!r} name must start with 'Sample - ' and be at most 100 characters")
        if entry["type"] not in {"OPENAPI", "ASYNCAPI", "ODCS"}:
            raise SampleError(f"sample {entry['key']!r} has an unsupported type")
        if entry["expectation"] not in {"clean", "warning", "error"}:
            raise SampleError(f"sample {entry['key']!r} has an unsupported expectation")
        match = SEMVER.fullmatch(entry["version"])
        if (
            len(entry["version"]) > 64
            or match is None
            or any(int(part) > 2_147_483_647 for part in match.groups()[:3])
        ):
            raise SampleError(f"sample {entry['key']!r} version must be valid SemVer 2.0")
        if entry["system"] not in SYSTEM_NAMES:
            raise SampleError(f"sample {entry['key']!r} has an unsupported system")
        if len(f"{SAMPLE_MARKER} — {entry['description']}") > 2000:
            raise SampleError(f"sample {entry['key']!r} description is too long")
        identity = (entry["system"], entry["name"], entry["version"])
        if identity in identities:
            raise SampleError(f"duplicate sample identity {identity!r}")
        candidate = (root / entry["file"]).resolve()
        if candidate.parent != specs_root or candidate.suffix not in {".yaml", ".yml", ".json"}:
            raise SampleError(f"sample {entry['key']!r} file must be directly under specs/")
        try:
            content = candidate.read_bytes().decode("utf-8")
        except OSError as exc:
            raise SampleError(f"could not read sample file {entry['file']!r}: {exc}") from exc
        keys.add(entry["key"])
        identities.add(identity)
        samples.append(Sample(**entry, content=content))
    return samples


def validate_expectation(sample: Sample, report: dict[str, Any], *, stored: bool = False) -> None:
    findings = report.get("findings")
    if not isinstance(findings, list):
        raise SampleError(f"{sample.key}: check response has no findings array")
    complete = report.get("checkComplete") if stored else report.get("checkerAvailable")
    unavailable = any(isinstance(f, dict) and f.get("code") == "CHECKER_UNAVAILABLE" for f in findings)
    if complete is not True or unavailable:
        raise SampleError(f"{sample.key}: checker result is incomplete; rerun after the checker recovers")
    syntax = [f for f in findings if isinstance(f, dict) and f.get("source") == "SYNTAX"]
    errors = [f for f in findings if isinstance(f, dict) and f.get("severity") == "ERROR"]
    warnings = [f for f in findings if isinstance(f, dict) and f.get("severity") == "WARN"]
    if syntax:
        raise SampleError(f"{sample.key}: syntax findings are never valid sample issues")
    valid = (
        (sample.expectation == "clean" and not errors and not warnings)
        or (sample.expectation == "warning" and not errors and bool(warnings))
        or (sample.expectation == "error" and bool(errors))
    )
    if not valid:
        raise SampleError(
            f"{sample.key}: expected {sample.expectation}, got {len(errors)} error(s) and {len(warnings)} warning(s)"
        )


def incomplete_stored_check(report: dict[str, Any]) -> bool:
    findings = report.get("findings")
    return report.get("checkComplete") is not True or (
        isinstance(findings, list)
        and any(isinstance(f, dict) and f.get("code") == "CHECKER_UNAVAILABLE" for f in findings)
    )


def exact(items: Iterable[dict[str, Any]], name: str, kind: str) -> dict[str, Any] | None:
    matches = [item for item in items if item.get("name") == name]
    if len(matches) > 1:
        raise SampleError(f"multiple {kind} records have the exact name {name!r}")
    return matches[0] if matches else None


def require_fields(record: dict[str, Any], expected: dict[str, Any], label: str) -> None:
    drift = [field for field, value in expected.items() if record.get(field) != value]
    if drift:
        raise SampleError(f"{label} already exists but differs in: {', '.join(drift)}; refusing to overwrite it")


class Loader:
    def __init__(self, client: ApiClient, samples: list[Sample], output: Callable[[str], None] = print):
        self.client = client
        self.samples = samples
        self.output = output
        self.created = 0
        self.skipped = 0

    def preflight(self) -> None:
        for sample in self.samples:
            report = self.client.json("POST", "/api/v1/contracts/versions/check", {
                "type": sample.type, "content": sample.content, "version": sample.version,
            })
            validate_expectation(sample, report)
            codes = sorted({str(f.get("code")) for f in report["findings"] if isinstance(f, dict)})
            shown = ", ".join(codes[:6]) or "none"
            if len(codes) > 6:
                shown += f", +{len(codes) - 6} more"
            self.output(f"checked {sample.key}: {sample.expectation}; findings {shown}")

    def _managed_registry(self, path: str, name: str, description: str, extra: dict[str, Any] | None = None) -> dict[str, Any]:
        params = {"name": name}
        if extra and "domainId" in extra:
            params["domainId"] = extra["domainId"]
        existing = exact(self.client.list_all(path, params), name, path.rsplit("/", 1)[-1])
        expected = {"name": name, "description": description, **(extra or {})}
        if existing:
            require_fields(existing, expected, name)
            self.skipped += 1
            return existing
        created = self.client.json("POST", path, expected)
        self.created += 1
        self.output(f"created {name} id={created.get('id')}")
        return created

    def load(self, user_id: int) -> None:
        domain = self._managed_registry("/api/v1/domains", DOMAIN_NAME, SAMPLE_MARKER)
        team_items = self.client.list_all("/api/v1/teams", {"name": TEAM_NAME})
        team = exact(team_items, TEAM_NAME, "teams")
        if team:
            detail = self.client.json("GET", f"/api/v1/teams/{team['id']}")
            require_fields(detail, {"name": TEAM_NAME, "description": SAMPLE_MARKER}, TEAM_NAME)
            members = {m.get("userId") for m in detail.get("members", []) if not m.get("deleted")}
            if members != {user_id}:
                raise SampleError(f"{TEAM_NAME} already exists but its active roster differs; refusing to overwrite it")
            self.skipped += 1
            team = detail
        else:
            team = self.client.json("POST", "/api/v1/teams", {
                "name": TEAM_NAME, "description": SAMPLE_MARKER, "memberIds": [user_id],
            })
            self.created += 1
            self.output(f"created {TEAM_NAME} id={team.get('id')}")

        systems: dict[str, dict[str, Any]] = {}
        for short_name, full_name in SYSTEM_NAMES.items():
            systems[short_name] = self._managed_registry(
                "/api/v1/systems", full_name, SAMPLE_MARKER, {"domainId": domain["id"]},
            )

        for sample in self.samples:
            system = systems[sample.system]
            contracts = self.client.list_all("/api/v1/contracts", {"systemId": system["id"], "q": sample.name})
            contract = exact(contracts, sample.name, "contracts")
            expected = {
                "systemId": system["id"], "type": sample.type, "name": sample.name,
                "description": sample.managed_description, "ownerTeamId": team["id"],
            }
            if contract:
                require_fields(contract, {
                    "name": sample.name, "type": sample.type, "description": sample.managed_description,
                }, sample.name)
                if contract.get("system", {}).get("id") != system["id"] or contract.get("owner") != {
                    "kind": "TEAM", "id": team["id"], "name": TEAM_NAME, "deleted": False,
                }:
                    raise SampleError(f"{sample.name} already exists with different system or ownership; refusing to overwrite it")
                self.skipped += 1
            else:
                contract = self.client.json("POST", "/api/v1/contracts", expected)
                self.created += 1
                self.output(f"created {sample.name} id={contract.get('id')}")

            versions = self.client.list_all(f"/api/v1/contracts/{contract['id']}/versions")
            version_matches = [item for item in versions if item.get("version") == sample.version]
            if len(version_matches) > 1:
                raise SampleError(f"{sample.key}: duplicate stored version {sample.version}")
            if version_matches:
                version_path = f"/api/v1/contracts/{contract['id']}/versions/{version_matches[0]['id']}"
                version = self.client.json("GET", version_path)
                expected_format = "json" if sample.content.lstrip().startswith(("{", "[")) else "yaml"
                require_fields(version, {
                    "contractId": contract["id"], "version": sample.version, "lifecycle": "DRAFT",
                    "format": expected_format, "sourceUrl": None,
                }, f"{sample.key} {sample.version}")
                content = version.get("content")
                if content is None:
                    content = self.client.text(f"/api/v1/contracts/{contract['id']}/versions/{version['id']}/content")
                if content != sample.content:
                    raise SampleError(f"{sample.key}: stored {sample.version} content differs byte-for-byte; refusing to overwrite it")
                if incomplete_stored_check(version):
                    self.output(f"rechecking {sample.key}: stored checker result is incomplete")
                    self.client.json("POST", version_path + "/recheck")
                    version = self.client.json("GET", version_path)
                    require_fields(version, {
                        "contractId": contract["id"], "version": sample.version, "lifecycle": "DRAFT",
                        "format": expected_format, "sourceUrl": None, "content": sample.content,
                    }, f"{sample.key} {sample.version}")
                validate_expectation(sample, version, stored=True)
                self.skipped += 1
                self.output(f"skipped {sample.key}: contract={contract['id']} version={version['id']} exact match")
                continue
            allow_invalid = "true" if sample.expectation == "error" else "false"
            version = self.client.json(
                "POST", f"/api/v1/contracts/{contract['id']}/versions?allowInvalid={allow_invalid}",
                {"version": sample.version, "content": sample.content},
            )
            if version.get("content") != sample.content:
                raise SampleError(f"{sample.key}: server did not return the created content byte-for-byte")
            validate_expectation(sample, version, stored=True)
            self.created += 1
            self.output(f"created {sample.key}: contract={contract['id']} version={version.get('id')}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"Covenant root URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("--allow-remote", action="store_true", help="deliberately allow a non-loopback target")
    parser.add_argument("--email", default=DEFAULT_EMAIL)
    parser.add_argument("--password-env", default=DEFAULT_PASSWORD_ENV, metavar="NAME")
    parser.add_argument("--check-only", "--dry-run", dest="check_only", action="store_true", help="authenticate and validate every sample without storing data")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        base_url = validate_base_url(args.base_url, args.allow_remote)
        root = Path(__file__).resolve().parent
        samples = load_manifest(root)
        print(f"Target: {base_url}")
        client = ApiClient(base_url, allow_remote=args.allow_remote)
        client.confirm_identity()
        password = os.environ.get(args.password_env)
        if password is None:
            password = getpass.getpass(f"Password for {args.email}: ")
        if not password:
            raise SampleError(f"password environment variable {args.password_env!r} is empty")
        session = client.login(args.email, password)
        loader = Loader(client, samples)
        loader.preflight()
        if args.check_only:
            print(f"Check only: {len(samples)} sample(s) passed; no data created")
            return 0
        if "ADMIN" not in session.get("roles", []):
            raise SampleError("loading registry samples requires an ADMIN account")
        user_id = session.get("userId")
        if not isinstance(user_id, int):
            raise SampleError("login response did not contain a numeric userId")
        loader.load(user_id)
        print(f"Done: created {loader.created}, skipped {loader.skipped}")
        return 0
    except (SampleError, UnicodeDecodeError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
