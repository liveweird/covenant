import unittest
import json
import tempfile
from pathlib import Path
from urllib.request import Request

import load


def finding(severity, source="LINT", code="sample-rule"):
    return {"severity": severity, "source": source, "code": code, "message": "sample"}


def sample(expectation="clean", content="openapi: 3.1.0\n"):
    return load.Sample(
        key="openapi-clean",
        name="Sample - Orders API",
        type="OPENAPI",
        version="1.0.0",
        file="specs/openapi-clean.yaml",
        expectation=expectation,
        description="A reusable example.",
        system="Orders",
        content=content,
    )


class BaseUrlTest(unittest.TestCase):
    def test_loopback_is_default_safety_boundary(self):
        self.assertEqual(load.validate_base_url("http://127.0.0.1:8082/"), "http://127.0.0.1:8082")
        with self.assertRaisesRegex(load.SampleError, "--allow-remote"):
            load.validate_base_url("https://contracts.example.com")
        self.assertEqual(
            load.validate_base_url("https://contracts.example.com", allow_remote=True),
            "https://contracts.example.com",
        )
        with self.assertRaisesRegex(load.SampleError, "must use HTTPS"):
            load.validate_base_url("http://contracts.example.com", allow_remote=True)

    def test_credentials_paths_and_fragments_are_rejected(self):
        for value in (
            "http://admin:secret@localhost:8082",
            "http://localhost:8082/api",
            "http://localhost:8082?target=x",
            "http://localhost:8082/#x",
        ):
            with self.subTest(value=value), self.assertRaises(load.SampleError):
                load.validate_base_url(value)


class RedirectTest(unittest.TestCase):
    def test_redirect_handler_refuses_every_redirect(self):
        request = Request(
            "http://localhost:8082/api/v1/contracts",
            headers={"Authorization": "Bearer sensitive-token"},
        )
        result = load.NoRedirects().redirect_request(
            request, None, 302, "Found", {}, "https://unexpected.example/contracts"
        )
        self.assertIsNone(result)


class PagingClient(load.ApiClient):
    def __init__(self):
        self.calls = []

    def json(self, method, path, payload=None):
        self.calls.append(path)
        if "page=1" in path:
            return {"items": [{"id": i} for i in range(100)], "page": 1, "pageSize": 100, "total": 101}
        return {"items": [{"id": 100}], "page": 2, "pageSize": 100, "total": 101}


class PaginationTest(unittest.TestCase):
    def test_list_all_fetches_every_server_page(self):
        client = PagingClient()
        items = client.list_all("/api/v1/contracts", {"q": "Sample - Orders API"})
        self.assertEqual(len(items), 101)
        self.assertIn("page=1", client.calls[0])
        self.assertIn("page=2", client.calls[1])


class ExpectationTest(unittest.TestCase):
    def test_each_expectation_is_classified_by_severity_not_count(self):
        cases = [
            ("clean", []),
            ("warning", [finding("WARN"), finding("INFO")]),
            ("error", [finding("ERROR", "SCHEMA"), finding("WARN")]),
        ]
        for expectation, findings in cases:
            with self.subTest(expectation=expectation):
                load.validate_expectation(
                    sample(expectation), {"checkerAvailable": True, "findings": findings}
                )

    def test_incomplete_or_syntax_result_is_always_refused(self):
        with self.assertRaisesRegex(load.SampleError, "incomplete"):
            load.validate_expectation(
                sample("error"),
                {"checkerAvailable": False, "findings": [finding("ERROR", "SYSTEM", "CHECKER_UNAVAILABLE")]},
            )


class ManifestTest(unittest.TestCase):
    def test_document_newlines_are_loaded_byte_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "specs").mkdir()
            content = b"openapi: 3.1.0\r\ninfo:\r\n  title: Example\r\n"
            (root / "specs" / "example.yaml").write_bytes(content)
            manifest = [{
                "key": "example", "name": "Sample - Example", "type": "OPENAPI",
                "version": "1.0.0", "file": "specs/example.yaml", "expectation": "clean",
                "description": "Example", "system": "Orders",
            }]
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(load.load_manifest(root)[0].content.encode(), content)

    def test_manifest_accepts_a_higher_semver(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "specs").mkdir()
            (root / "specs" / "example.yaml").write_text("openapi: 3.1.0\n", encoding="utf-8")
            manifest = [{
                "key": "example", "name": "Sample - Example", "type": "OPENAPI",
                "version": "1.1.0", "file": "specs/example.yaml", "expectation": "clean",
                "description": "Example", "system": "Orders",
            }]
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(load.load_manifest(root)[0].version, "1.1.0")

    def test_managed_description_is_single_line(self):
        description = sample().managed_description
        self.assertNotRegex(description, r"[\x00-\x1f\x7f]")
        self.assertIn(load.SAMPLE_MARKER, description)
        self.assertIn(sample().description, description)
        with self.assertRaisesRegex(load.SampleError, "syntax"):
            load.validate_expectation(
                sample("error"), {"checkerAvailable": True, "findings": [finding("ERROR", "SYNTAX")]}
            )


class FakeExistingClient:
    def __init__(self, contract_description=None):
        self.posts = []
        self.contract_description = contract_description or sample().managed_description

    def list_all(self, path, params=None):
        if path == "/api/v1/domains":
            return [{"id": 10, "name": load.DOMAIN_NAME, "description": load.SAMPLE_MARKER}]
        if path == "/api/v1/teams":
            return [{"id": 20, "name": load.TEAM_NAME, "description": load.SAMPLE_MARKER}]
        if path == "/api/v1/systems":
            name = params["name"]
            identifier = 30 if name == load.SYSTEM_NAMES["Orders"] else 31
            return [{"id": identifier, "domainId": 10, "name": name, "description": load.SAMPLE_MARKER}]
        if path == "/api/v1/contracts":
            return [{
                "id": 40,
                "name": sample().name,
                "type": "OPENAPI",
                "description": self.contract_description,
                "system": {"id": 30, "name": load.SYSTEM_NAMES["Orders"]},
                "owner": {"kind": "TEAM", "id": 20, "name": load.TEAM_NAME, "deleted": False},
            }]
        if path == "/api/v1/contracts/40/versions":
            return [{"id": 50, "version": "1.0.0"}]
        raise AssertionError((path, params))

    def json(self, method, path, payload=None):
        if method == "POST":
            self.posts.append((path, payload))
            raise AssertionError("idempotent load attempted a write")
        if path == "/api/v1/teams/20":
            return {
                "id": 20, "name": load.TEAM_NAME, "description": load.SAMPLE_MARKER,
                "members": [{"userId": 7, "deleted": False}],
            }
        if path == "/api/v1/contracts/40/versions/50":
            return {
                "id": 50, "contractId": 40, "version": "1.0.0", "lifecycle": "DRAFT",
                "format": "yaml", "sourceUrl": None, "content": sample().content,
                "checkComplete": True, "findings": [],
            }
        raise AssertionError((method, path, payload))


class FakeFreshContractClient(FakeExistingClient):
    def __init__(self, current_sample):
        super().__init__()
        self.current_sample = current_sample

    def list_all(self, path, params=None):
        if path == "/api/v1/contracts" or path == "/api/v1/contracts/40/versions":
            return []
        return super().list_all(path, params)

    def json(self, method, path, payload=None):
        if method == "POST" and path == "/api/v1/contracts":
            self.posts.append((path, payload))
            return {"id": 40}
        if method == "POST" and path.startswith("/api/v1/contracts/40/versions?"):
            self.posts.append((path, payload))
            findings = {
                "clean": [],
                "warning": [finding("WARN")],
                "error": [finding("ERROR", "SCHEMA")],
            }[self.current_sample.expectation]
            return {
                "id": 50, "contractId": 40, "version": "1.0.0", "lifecycle": "DRAFT",
                "format": "yaml", "sourceUrl": None, "content": self.current_sample.content,
                "checkComplete": True, "findings": findings,
            }
        return super().json(method, path, payload)


class FakeIncompleteClient(FakeExistingClient):
    def __init__(self):
        super().__init__()
        self.version_reads = 0

    def json(self, method, path, payload=None):
        if path == "/api/v1/contracts/40/versions/50" and method == "GET":
            self.version_reads += 1
            version = super().json(method, path, payload)
            if self.version_reads == 1:
                version = dict(version, checkComplete=False, findings=[
                    finding("INFO", "SYSTEM", "CHECKER_UNAVAILABLE")
                ])
            return version
        if path == "/api/v1/contracts/40/versions/50/recheck" and method == "POST":
            self.posts.append((path, payload))
            return {"id": 50}
        return super().json(method, path, payload)


class IdempotencyTest(unittest.TestCase):
    def test_incomplete_exact_version_is_rechecked_and_refetched(self):
        client = FakeIncompleteClient()
        loader = load.Loader(client, [sample()], lambda _message: None)
        loader.load(user_id=7)
        self.assertEqual(client.version_reads, 2)
        self.assertEqual(client.posts, [("/api/v1/contracts/40/versions/50/recheck", None)])

    def test_first_load_waives_only_expected_errors(self):
        for expectation, flag in (("clean", "false"), ("warning", "false"), ("error", "true")):
            with self.subTest(expectation=expectation):
                current = sample(expectation)
                client = FakeFreshContractClient(current)
                loader = load.Loader(client, [current], lambda _message: None)
                loader.load(user_id=7)
                version_posts = [path for path, _body in client.posts if "/versions?" in path]
                self.assertEqual(version_posts, [f"/api/v1/contracts/40/versions?allowInvalid={flag}"])

    def test_exact_existing_records_are_verified_without_writes(self):
        client = FakeExistingClient()
        messages = []
        loader = load.Loader(client, [sample()], messages.append)
        loader.load(user_id=7)
        self.assertEqual(client.posts, [])
        self.assertIn("exact match", messages[-1])
        self.assertEqual(loader.created, 0)

    def test_contract_metadata_drift_is_refused_without_writes(self):
        client = FakeExistingClient(contract_description="Somebody else's record")
        loader = load.Loader(client, [sample()], lambda _message: None)
        with self.assertRaisesRegex(load.SampleError, "differs in: description"):
            loader.load(user_id=7)
        self.assertEqual(client.posts, [])


if __name__ == "__main__":
    unittest.main()
