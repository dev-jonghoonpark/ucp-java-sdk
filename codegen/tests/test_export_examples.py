# Copyright 2026 UCP Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Tests for export_examples.py.

The exporter imports the specification repo's scripts/validate_examples.py at
run time. The tests stand that module in with a small stub implementing the
same functions, so they need neither the spec repo nor network access.

Run: python3 -m unittest discover -s codegen/tests
"""

import json
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import export_examples as ex  # noqa: E402

# A stand-in for the spec repo's scripts/validate_examples.py. Blocks are
# written to docs as JSON lines: {"line": .., "annotation": .., "content": ..}.
STUB_VALIDATE_EXAMPLES = textwrap.dedent('''
    import json

    def extract_blocks(path):
        return [json.loads(line) for line in path.read_text().splitlines() if line]

    def parse_example(raw):
        return json.loads(raw)

    def jsonpath_get(obj, path):
        for part in [p for p in path.lstrip("$").split(".") if p]:
            obj = obj[part]
        return obj

    def jsonpath_set(obj, path, value):
        parts = [p for p in path.lstrip("$").split(".") if p]
        for part in parts[:-1]:
            obj = obj[part]
        obj[parts[-1]] = value

    def jsonpath_to_pointer(path):
        return "".join("/" + p for p in path.lstrip("$").split(".") if p)

    def strip_ellipsis(obj, path=""):
        if isinstance(obj, dict):
            out, elided = {}, set()
            for k, v in obj.items():
                if v == "...":
                    elided.add(f"{path}/{k}")
                    continue
                out[k], sub = strip_ellipsis(v, f"{path}/{k}")
                elided |= sub
            return out, elided
        return obj, set()

    def load_scaffold(schema, direction, op, scaffolds):
        f = scaffolds / f"{schema.replace('/', '_')}_{direction}.json"
        return json.loads(f.read_text()) if f.exists() else None

    def deep_merge(scaffold, example):
        if isinstance(scaffold, dict) and isinstance(example, dict):
            out = dict(scaffold)
            for k, v in example.items():
                out[k] = deep_merge(out[k], v) if k in out else v
            return out
        return example
''')


def write(root, rel, content):
    path = Path(root) / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content if isinstance(content, str) else json.dumps(content),
                    encoding="utf-8")


class ModelLocationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        write(self.dir, "shopping/checkout.json", {"type": "object", "properties": {}})
        write(self.dir, "shopping/checkout_create_request.json",
              {"type": "object", "properties": {}, "$defs": {"inner": {}}})
        write(self.dir, "shopping/catalog.json",
              {"$defs": {"search_request": {}, "search_response": {}}})
        write(self.dir, "shopping/consent.json",
              {"type": "object", "properties": {}, "$defs": {"purpose": {}}})

    def tearDown(self):
        self.tmp.cleanup()

    def locate(self, schema, op="read", direction="response", schema_def=None):
        return ex.model_location(self.dir, schema, op, direction, schema_def)

    def test_response_maps_to_the_base_schema(self):
        self.assertEqual(self.locate("shopping/checkout"), ("shopping/checkout.json", True))

    def test_request_maps_to_its_generated_variant(self):
        self.assertEqual(self.locate("shopping/checkout", "create", "request"),
                         ("shopping/checkout_create_request.json", True))

    def test_request_without_variant_falls_back_and_is_marked_inexact(self):
        self.assertEqual(self.locate("shopping/checkout", "update", "request"),
                         ("shopping/checkout.json", False))

    def test_read_requests_are_never_variants(self):
        self.assertEqual(self.locate("shopping/checkout", "read", "request"),
                         ("shopping/checkout.json", True))

    def test_def_selects_a_definition_in_the_chosen_file(self):
        self.assertEqual(self.locate("shopping/checkout", "create", "request", "inner"),
                         ("shopping/checkout_create_request.json#/$defs/inner", True))
        self.assertEqual(self.locate("shopping/consent", schema_def="purpose"),
                         ("shopping/consent.json#/$defs/purpose", True))

    def test_unknown_def_yields_no_location(self):
        self.assertEqual(self.locate("shopping/consent", schema_def="nope"), (None, True))

    def test_container_schema_uses_op_and_direction_def(self):
        self.assertEqual(self.locate("shopping/catalog", "search", "request"),
                         ("shopping/catalog.json#/$defs/search_request", True))

    def test_container_without_matching_def_maps_to_the_file(self):
        self.assertEqual(self.locate("shopping/catalog", "lookup", "response"),
                         ("shopping/catalog.json", True))

    def test_missing_schema_file_raises(self):
        with self.assertRaises(FileNotFoundError):
            self.locate("shopping/nope")


class ExportTest(unittest.TestCase):
    """Runs main() end to end over a fake spec repo."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name) / "ucp"
        self.schemas = Path(self.tmp.name) / "schemas"
        self.out = Path(self.tmp.name) / "out" / "examples.json"
        write(self.repo, "scripts/validate_examples.py", STUB_VALIDATE_EXAMPLES)
        write(self.repo, "scripts/scaffolds/shopping_checkout_response.json",
              {"id": "scaffold", "status": "incomplete", "nested": {"a": 1}})
        write(self.schemas, "shopping/checkout.json", {"type": "object", "properties": {}})
        write(self.schemas, "shopping/checkout_create_request.json",
              {"type": "object", "properties": {}})
        sys.modules.pop("validate_examples", None)

    def tearDown(self):
        self.tmp.cleanup()
        sys.modules.pop("validate_examples", None)
        while str(self.repo / "scripts") in sys.path:
            sys.path.remove(str(self.repo / "scripts"))

    def blocks(self, *blocks):
        write(self.repo, "docs/spec.md", "\n".join(json.dumps(b) for b in blocks))

    def run_main(self):
        argv = ["export_examples.py", str(self.repo), str(self.schemas), str(self.out)]
        with mock.patch.object(sys, "argv", argv), mock.patch("builtins.print") as printed:
            ex.main()
        return json.loads(self.out.read_text()), printed.call_args[0][0]

    @staticmethod
    def block(line, content, **annotation):
        annotation.setdefault("op", "read")
        annotation.setdefault("direction", "response")
        return {"line": line, "annotation": annotation, "content": json.dumps(content)}

    def test_example_is_merged_into_scaffold_and_recorded(self):
        # Arrange
        self.blocks(self.block(10, {"id": "chk_1", "nested": {"b": 2}},
                               schema="shopping/checkout"))

        # Act
        examples, summary = self.run_main()

        # Assert
        self.assertEqual(examples, [{
            "source": "docs/spec.md:10",
            "schema": "shopping/checkout.json",
            "exact": True,
            "elided": [],
            "payload": {"id": "chk_1", "status": "incomplete", "nested": {"a": 1, "b": 2}},
        }])
        self.assertEqual(summary, f"Exported 1 examples (0 skipped) to {self.out}")

    def test_ellipsis_paths_are_recorded_and_stripped(self):
        self.blocks(self.block(1, {"id": "x", "buyer": "..."}, schema="shopping/checkout"))

        examples, _ = self.run_main()

        self.assertEqual(examples[0]["elided"], ["/buyer"])
        self.assertNotIn("buyer", examples[0]["payload"])

    def test_target_inserts_into_scaffold_and_prefixes_elisions(self):
        self.blocks(self.block(1, {"b": 2, "c": "..."}, schema="shopping/checkout",
                               target="$.nested"))

        examples, _ = self.run_main()

        self.assertEqual(examples[0]["payload"]["nested"], {"b": 2})
        self.assertEqual(examples[0]["elided"], ["/nested/c"])

    def test_extract_selects_the_payload_from_an_envelope(self):
        self.blocks(self.block(1, {"result": {"id": "x"}}, schema="shopping/checkout",
                               extract="$.result"))

        examples, _ = self.run_main()

        self.assertEqual(examples[0]["payload"]["id"], "x")

    def test_request_example_uses_variant(self):
        self.blocks(self.block(1, {"line_items": []}, schema="shopping/checkout",
                               op="create", direction="request"))

        examples, _ = self.run_main()

        self.assertEqual(examples[0]["schema"], "shopping/checkout_create_request.json")
        self.assertEqual(examples[0]["payload"], {"line_items": []})

    def test_unusable_blocks_are_skipped_and_counted(self):
        # Arrange: skip annotation, no annotation, no schema, error block, bad JSON,
        # bad extract path, unknown def, failing target.
        self.blocks(
            {"line": 1, "annotation": {"skip": True}, "content": "{}"},
            {"line": 2, "annotation": None, "content": "{}"},
            self.block(3, {"a": 1}),
            {"line": 4, "error": "stacked", "annotation": {"schema": "x"}, "content": "{}"},
            {"line": 5, "annotation": {"schema": "shopping/checkout", "op": "read",
                                       "direction": "response"}, "content": "{not json"},
            self.block(6, {"a": 1}, schema="shopping/checkout", extract="$.missing"),
            self.block(7, {"a": 1}, schema="shopping/checkout", **{"def": "nope"}),
            self.block(8, {"a": 1}, schema="shopping/checkout", target="$.missing.deeper"),
        )

        # Act
        examples, summary = self.run_main()

        # Assert
        self.assertEqual(examples, [])
        self.assertEqual(summary, f"Exported 0 examples (8 skipped) to {self.out}")

    def test_empty_bodies_are_neither_exported_nor_skipped(self):
        self.blocks(self.block(1, {}, schema="shopping/checkout"))

        examples, summary = self.run_main()

        self.assertEqual(examples, [])
        self.assertIn("(0 skipped)", summary)

    def test_wrong_argument_count_exits_with_usage(self):
        with mock.patch.object(sys, "argv", ["export_examples.py"]), \
                mock.patch("builtins.print"):
            with self.assertRaises(SystemExit) as raised:
                ex.main()
        self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
