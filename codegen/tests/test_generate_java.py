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

"""Tests for generate_java.py.

Helpers are tested directly; mapping rules are tested end to end by running
the generator over small synthetic schema trees and inspecting the Java it
renders. Whether the output compiles is covered by the Gradle build, which
compiles the models generated from the real specification.

Run: python3 -m unittest discover -s codegen/tests
"""

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import generate_java as g  # noqa: E402

PKG = "dev.example.models"


def write_schemas(root, files):
    for rel, doc in files.items():
        path = Path(root) / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(doc), encoding="utf-8")


def generate(files):
    """Runs the generator over `files` and returns {fqn: java source}."""
    g.REGISTRY.clear()
    g.ALIASES.clear()
    with tempfile.TemporaryDirectory() as tmp:
        write_schemas(tmp, files)
        gen = g.Generator(g.Schemas(tmp), PKG)
        gen.run()
        return {t.fqn(): g.Renderer(t, gen.tops, gen.s.docs).render() for t in gen.tops}


def obj(properties, required=(), **extra):
    return {"type": "object", "properties": properties, "required": list(required), **extra}


class NamingTest(unittest.TestCase):
    def test_pascal_splits_on_separators_and_case(self):
        self.assertEqual(g.pascal("line_items"), "LineItems")
        self.assertEqual(g.pascal("dev.ucp.shopping"), "DevUcpShopping")
        self.assertEqual(g.pascal("contentType"), "ContentType")

    def test_pascal_edge_cases(self):
        self.assertEqual(g.pascal(""), "Value")
        self.assertEqual(g.pascal("2fa"), "_2fa")

    def test_camel_escapes_java_keywords(self):
        self.assertEqual(g.camel("expires_at"), "expiresAt")
        self.assertEqual(g.camel("default"), "default_")
        self.assertEqual(g.camel("class"), "class_")

    def test_constant_for_enum_values(self):
        self.assertEqual(g.constant("requires_buyer_input"), "REQUIRES_BUYER_INPUT")
        self.assertEqual(g.constant("3ds"), "_3DS")
        self.assertEqual(g.constant(""), "EMPTY")
        # Java keywords are lowercase, so upper-case constants never need escaping.
        self.assertEqual(g.constant("new"), "NEW")

    def test_singular(self):
        cases = {"lines": "Line", "categories": "Category", "addresses": "Address",
                 "boxes": "Box", "batches": "Batch", "status": "Statu",
                 "glass": "GlassItem", "media": "MediaItem"}
        for plural, expected in cases.items():
            with self.subTest(plural=plural):
                self.assertEqual(g.singular(plural), expected)


class JavadocTest(unittest.TestCase):
    def test_escapes_html_and_comment_terminators(self):
        doc = g.javadoc("a < b && c > d */ @foo \\u0041", 0)
        self.assertIn("a &lt; b &amp;&amp; c &gt; d *&#47; &#64;foo \\\\u0041", doc)
        self.assertNotIn("*/ @", doc)

    def test_extra_paragraphs_get_p_tags_but_block_tags_do_not(self):
        doc = g.javadoc("Body.", 4, ["Required.", "@deprecated"])
        self.assertIn("     * <p>Required.", doc)
        self.assertIn("     * @deprecated", doc)
        self.assertTrue(doc.startswith("    /**\n"))

    def test_empty_produces_nothing(self):
        self.assertEqual(g.javadoc(None, 0), "")
        self.assertEqual(g.javadoc("", 0, []), "")


class SchemaHelpersTest(unittest.TestCase):
    def test_deep_merge_unions_required_and_overrides_scalars(self):
        base = {"required": ["a"], "properties": {"a": {"type": "string", "x": 1}}}
        over = {"required": ["b", "a"], "properties": {"a": {"x": 2}}}
        merged = g.deep_merge(base, over)
        self.assertEqual(merged["required"], ["a", "b"])
        self.assertEqual(merged["properties"]["a"], {"type": "string", "x": 2})
        self.assertEqual(base["properties"]["a"]["x"], 1, "inputs must not be mutated")

    def test_normalize_path(self):
        self.assertEqual(g.normalize_path("shopping/types/../../ucp.json"), "ucp.json")
        self.assertEqual(g.normalize_path("./a//b/./c.json"), "a/b/c.json")

    def test_resolve_follows_relative_refs_and_pointers(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_schemas(tmp, {
                "a/x.json": {"$defs": {"k": {"type": "string"}}},
                "b/y.json": {"type": "object"},
            })
            schemas = g.Schemas(tmp)
            loc = schemas.resolve("../a/x.json#/$defs/k", "b/y.json")
            self.assertEqual((loc.file, loc.pointer), ("a/x.json", "/$defs/k"))
            self.assertEqual(loc.node, {"type": "string"})
            with self.assertRaises(KeyError):
                schemas.resolve("missing.json", "b/y.json")


class ClassGenerationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = generate({
            "order.json": obj(
                {
                    "id": {"type": "string", "description": "Order id."},
                    "status": {"type": "string", "enum": ["open", "closed"]},
                    "kind": {"type": "string", "const": "order"},
                    "note": {"type": "string", "const": "optional"},
                    "created_at": {"type": "string", "format": "date-time"},
                    "url": {"type": "string", "format": "uri"},
                    "count": {"type": "integer"},
                    "ratio": {"type": "number"},
                    "nullable": {"type": ["string", "null"]},
                    "lines": {"type": "array", "items": obj({"sku": {"type": "string"}})},
                    "tags": {"type": "object", "additionalProperties": {"type": "string"}},
                    "default": {"type": "boolean", "default": False},
                    "money": {"$ref": "amount.json"},
                },
                required=["id", "kind"],
                description="An order.",
                **{"$defs": {"version": {"type": "string", "pattern": "^\\d+$"}}},
            ),
            "amount.json": {"type": "integer", "minimum": 0},
            "closed.json": obj({"a": {"type": "string"}}, additionalProperties=False),
        })
        cls.order = cls.out[f"{PKG}.Order"]

    def test_field_types(self):
        for decl in ["private String id;", "private Order.Status status;",
                     "private OffsetDateTime createdAt;", "private URI url;",
                     "private Long count;", "private Double ratio;",
                     "private String nullable;", "private List<Order.Line> lines;",
                     "private Map<String, String> tags;", "private Boolean default_;",
                     "private Long money;"]:
            with self.subTest(decl=decl):
                self.assertIn(decl, self.order)

    def test_required_constant_is_prefilled_but_optional_is_not(self):
        self.assertIn('private String kind = "order";', self.order)
        self.assertIn("private String note;", self.order)

    def test_required_properties_are_annotated(self):
        self.assertIn('@JsonProperty(value = "id", required = true)', self.order)
        self.assertIn('@JsonProperty(value = "status")', self.order)

    def test_accessors(self):
        self.assertIn("public String getId()", self.order)
        self.assertIn("public void setId(String id)", self.order)
        self.assertIn("public Order id(String id)", self.order)
        self.assertIn("public Boolean getDefault()", self.order)

    def test_nested_inline_types_and_enum(self):
        self.assertIn("public static class Line {", self.order)
        self.assertIn("public enum Status {", self.order)
        self.assertIn('OPEN("open")', self.order)
        self.assertNotIn("@JsonCreator", self.order)
        self.assertIn("@JsonValue", self.order)

    def test_open_by_default_and_closed_when_declared(self):
        self.assertIn("@JsonAnySetter", self.order)
        closed = self.out[f"{PKG}.Closed"]
        self.assertNotIn("@JsonAnySetter", closed)
        self.assertNotIn("additionalProperties", closed)

    def test_schema_annotation_and_docs(self):
        self.assertIn('@UcpSchema("order.json")', self.order)
        self.assertIn('@UcpSchema("order.json#/properties/lines/items")', self.order)
        self.assertIn(" * An order.", self.order)
        # Without a description the note is the first paragraph, so it gets no <p>.
        self.assertIn("     * Default: {@code false}.", self.order)
        self.assertIn("     * <p>Required.", self.order)

    def test_primitive_schemas_generate_no_type(self):
        self.assertNotIn(f"{PKG}.Amount", self.out)
        self.assertNotIn("class Version", self.order)

    def test_registry_records_generated_locations(self):
        self.assertEqual(g.REGISTRY["order.json"], f"{PKG}.Order")
        self.assertEqual(g.REGISTRY["order.json#/properties/lines/items"], f"{PKG}.Order.Line")


class AllOfTest(unittest.TestCase):
    def test_allof_flattens_across_files_and_rebases_refs(self):
        out = generate({
            "base.json": obj({"id": {"type": "string"}, "unit": {"$ref": "types/unit.json"}},
                             required=["id"]),
            "types/unit.json": obj({"code": {"type": "string"}}),
            "sub/child.json": {
                "type": "object",
                "allOf": [{"$ref": "../base.json"}],
                "properties": {"extra": {"type": "integer"}},
            },
        })
        child = out[f"{PKG}.sub.Child"]
        self.assertIn('@JsonPropertyOrder({"id", "unit", "extra"})', child)
        self.assertIn("private Unit unit;", child)
        self.assertIn(f"import {PKG}.types.Unit;", child)
        self.assertIn('@JsonProperty(value = "id", required = true)', child)

    def test_conditional_branches_are_left_to_the_validator(self):
        out = generate({"c.json": {
            "type": "object", "properties": {"t": {"type": "string"}},
            "allOf": [{"if": {"properties": {"t": {"const": "x"}}},
                       "then": {"required": ["y"]}}],
        }})
        self.assertNotIn('"y"', out[f"{PKG}.C"])


class UnionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        variant = lambda tag: obj({"type": {"type": "string", "const": tag},  # noqa: E731
                                   "body": {"type": "string"}}, required=["type"])
        cls.out = generate({
            "msg.json": {"oneOf": [{"$ref": "msg_a.json"}, {"$ref": "msg_b.json"}],
                         "description": "A message."},
            "msg_a.json": variant("a"),
            "msg_b.json": variant("b"),
            "mixed.json": obj({"id": {"oneOf": [{"type": "string"}, {"type": "number"}]},
                               "same": {"anyOf": [{"type": "string"}, {"type": "string"}]}}),
            "needs.json": {"type": "object", "properties": {"a": {"type": "string"},
                                                             "b": {"type": "string"}},
                           "anyOf": [{"required": ["a"]}, {"required": ["b"]}]},
        })

    def test_object_union_becomes_interface_with_variant_table(self):
        msg = self.out[f"{PKG}.Msg"]
        self.assertIn("public interface Msg {", msg)
        self.assertIn("@JsonDeserialize(using = Msg.Deserializer.class)", msg)
        self.assertIn('variant(MsgA.class, List.of("type"), '
                      'Map.ofEntries(Map.entry("type", "a")))', msg)

    def test_variants_implement_the_union_and_opt_out_of_its_deserializer(self):
        a = self.out[f"{PKG}.MsgA"]
        self.assertIn("public class MsgA implements Msg {", a)
        self.assertIn("@JsonDeserialize(using = JsonDeserializer.None.class)", a)

    def test_primitive_unions_fall_back_or_collapse(self):
        mixed = self.out[f"{PKG}.Mixed"]
        self.assertIn("private JsonNode id;", mixed)
        self.assertIn("private String same;", mixed)

    def test_required_only_branches_merge_into_the_object(self):
        needs = self.out[f"{PKG}.Needs"]
        self.assertIn("public class Needs {", needs)
        self.assertIn('@JsonProperty(value = "a")', needs)

    def test_more_than_ten_constants_compile_via_map_of_entries(self):
        props = {f"c{i}": {"type": "string", "const": f"v{i}"} for i in range(12)}
        out = generate({
            "u.json": {"oneOf": [{"$ref": "wide.json"}, {"$ref": "msg_b.json"}]},
            "wide.json": obj(props, required=list(props)),
            "msg_b.json": obj({"type": {"type": "string", "const": "b"}}, required=["type"]),
        })
        row = next(ln for ln in out[f"{PKG}.U"].splitlines() if "Wide.class" in ln)
        self.assertEqual(row.count("Map.entry("), 12)
        self.assertNotIn("Map.of(", row)


class HoistingTest(unittest.TestCase):
    def self_referential(self, extra_files=None):
        files = {
            "expr.json": {
                "type": "object", "additionalProperties": False,
                "properties": {"props": {"type": "object", "additionalProperties": {
                    "oneOf": [{"$ref": "#"}, {"$ref": "#/$defs/leaf"}]}}},
                "$defs": {"leaf": obj({"enum": {"type": "array"}})},
            },
        }
        files.update(extra_files or {})
        return generate(files)

    def test_union_containing_its_enclosing_class_is_hoisted(self):
        out = self.self_referential()
        self.assertIn(f"{PKG}.ExprPropsValue", out)
        self.assertIn("public class Expr implements ExprPropsValue {", out[f"{PKG}.Expr"])

    def test_hoisted_name_avoids_existing_file_types(self):
        out = self.self_referential({"expr_props_value.json": obj({"x": {"type": "string"}})})
        self.assertIn(f"{PKG}.ExprPropsValue", out)  # the file's own class
        self.assertIn(f"{PKG}.ExprPropsValue2", out)  # the hoisted union
        self.assertIn("public class ExprPropsValue {", out[f"{PKG}.ExprPropsValue"])
        self.assertIn("public interface ExprPropsValue2 {", out[f"{PKG}.ExprPropsValue2"])


class DefsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = generate({
            "catalog.json": {"$defs": {
                "search_request": obj({"query": {"type": "string"}}),
                "consent": {"type": "object", "additionalProperties": {"$ref": "#/$defs/purpose"}},
                "purpose": obj({"granted": {"type": "boolean"}}),
                "alias": {"$ref": "item.json", "description": "Same as item."},
                "catalog": obj({"x": {"type": "string"}}),
            }},
            "item.json": obj({"id": {"type": "string"}}),
        })
        cls.catalog = cls.out[f"{PKG}.Catalog"]

    def test_defs_only_file_becomes_holder_with_nested_types(self):
        self.assertIn("public final class Catalog {", self.catalog)
        self.assertIn("private Catalog() {}", self.catalog)
        self.assertIn("public static class SearchRequest {", self.catalog)

    def test_nested_type_named_like_its_holder_is_renamed(self):
        self.assertIn("public static class CatalogDef {", self.catalog)

    def test_named_map_becomes_linked_hash_map_subclass(self):
        self.assertIn(
            "public static class Consent extends LinkedHashMap<String, Catalog.Purpose> {",
            self.catalog)

    def test_alias_def_registers_the_target_model(self):
        # Aliases are folded into the registry when it is written.
        with tempfile.TemporaryDirectory() as tmp:
            g.write_registry(Path(tmp), PKG, "2026-08-25")
        self.assertEqual(g.REGISTRY["catalog.json#/$defs/alias"], f"{PKG}.Item")
        self.assertNotIn("class Alias", self.catalog)

    def test_defs_render_in_schema_order(self):
        order = [m.group(1) for m in
                 re.finditer(r"public static class (\w+)", self.catalog)]
        self.assertEqual(order, ["SearchRequest", "Consent", "Purpose", "CatalogDef"])


class MainTest(unittest.TestCase):
    def run_main(self, schemas, out_dir, run_patch=None):
        argv = ["generate_java.py", schemas, out_dir, PKG, "2026-08-25"]
        g.REGISTRY.clear()
        g.ALIASES.clear()
        with mock.patch.object(sys, "argv", argv), mock.patch("builtins.print"):
            if run_patch:
                with mock.patch.object(g.Generator, "run", run_patch):
                    g.main()
            else:
                g.main()

    def test_writes_files_and_registry_with_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_schemas(f"{tmp}/s", {"shop/cart.json": obj({"id": {"type": "string"}})})
            self.run_main(f"{tmp}/s", f"{tmp}/out")
            base = Path(tmp, "out", *PKG.split("."))
            self.assertTrue((base / "shop" / "Cart.java").exists())
            registry = (base / "UcpModels.java").read_text(encoding="utf-8")
            self.assertIn('SCHEMA_VERSION = "2026-08-25"', registry)
            self.assertIn(f'Map.entry("shop/cart.json", {PKG}.shop.Cart.class)', registry)

    def test_two_types_mapping_to_one_file_fail_the_run(self):
        original_run = g.Generator.run

        def run_with_duplicate(self):
            original_run(self)
            self.tops.append(self.tops[0])

        with tempfile.TemporaryDirectory() as tmp:
            write_schemas(f"{tmp}/s", {"a.json": obj({"x": {"type": "string"}})})
            with self.assertRaises(SystemExit) as raised:
                self.run_main(f"{tmp}/s", f"{tmp}/out", run_with_duplicate)
            self.assertIn("two top-level types map to", str(raised.exception))

    def test_wrong_argument_count_exits_with_usage(self):
        with mock.patch.object(sys, "argv", ["generate_java.py"]), \
                mock.patch("builtins.print") as printed:
            with self.assertRaises(SystemExit) as raised:
                g.main()
        self.assertEqual(raised.exception.code, 2)
        self.assertIn("Usage", printed.call_args[0][0])


if __name__ == "__main__":
    unittest.main()
