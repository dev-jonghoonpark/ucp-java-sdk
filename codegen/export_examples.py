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

"""Exports the specification's annotated JSON examples as a test corpus.

Every ```json block in the UCP docs carries a `<!-- ucp:example schema=...
op=... direction=... -->` annotation naming the schema it conforms to. This
script reuses the specification's own example reducer
(scripts/validate_examples.py) to turn each block into canonical JSON, merges
it into the matching scaffold exactly as the spec CI does, and records the
schema location the Java SDK models it with.

Usage: export_examples.py <ucp_repo_root> <preprocessed_schema_dir> <out.json>
"""

import json
import sys
from pathlib import Path


def model_location(schemas_dir, schema, op, direction, schema_def):
    """Maps an example annotation to the SDK schema location it targets.

    Returns (location, exact). `exact` is False for a create/update/complete
    request example of a schema that has no request variant: it is then
    modeled by the response schema, whose required set can be stricter.
    """
    file = f"{schema}.json"
    exact = True
    if direction == "request" and op in ("create", "update", "complete"):
        variant = f"{schema}_{op}_request.json"
        if (schemas_dir / variant).exists():
            file = variant
        else:
            exact = False
    doc = json.loads((schemas_dir / file).read_text(encoding="utf-8"))
    defs = doc.get("$defs", {})
    if schema_def:
        return (f"{file}#/$defs/{schema_def}", exact) if schema_def in defs else (None, exact)
    op_key = f"{op}_{direction}"
    if "properties" not in doc and op_key in defs:
        return f"{file}#/$defs/{op_key}", exact
    return file, exact


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    repo, schemas_dir, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    sys.path.insert(0, str(repo / "scripts"))
    import validate_examples as ve  # noqa: PLC0415 (spec repo module)

    scaffolds = repo / "scripts" / "scaffolds"
    examples = []
    skipped = 0
    for md in sorted((repo / "docs").rglob("*.md")):
        for block in ve.extract_blocks(md):
            ann = block.get("annotation")
            if block.get("error") or not ann or ann.get("_error") or ann.get("skip"):
                skipped += 1
                continue
            if not ann.get("schema"):
                skipped += 1
                continue
            op, direction = ann["op"], ann["direction"]
            try:
                parsed = ve.parse_example(block["content"])
                example = ve.jsonpath_get(parsed, ann.get("extract", "$"))
            except Exception:  # noqa: BLE001 - malformed blocks are the spec's problem
                skipped += 1
                continue
            if example == {}:
                continue
            stripped, elided = ve.strip_ellipsis(example)
            target = ann.get("target")
            scaffold = ve.load_scaffold(ann["schema"], direction, op, scaffolds) or {}
            if target:
                merged = json.loads(json.dumps(scaffold))
                try:
                    ve.jsonpath_set(merged, target, stripped)
                except (KeyError, IndexError, TypeError):
                    skipped += 1
                    continue
                prefix = ve.jsonpath_to_pointer(target)
                elided = {prefix + p for p in elided}
            else:
                merged = ve.deep_merge(scaffold, stripped)
            location, exact = model_location(
                schemas_dir, ann["schema"], op, direction, ann.get("def"))
            if location is None:
                skipped += 1
                continue
            examples.append({
                "source": f"{md.relative_to(repo).as_posix()}:{block['line']}",
                "schema": location,
                "exact": exact,
                "elided": sorted(elided),
                "payload": merged,
            })
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(examples, indent=1, ensure_ascii=False) + "\n",
                   encoding="utf-8")
    print(f"Exported {len(examples)} examples ({skipped} skipped) to {out}")


if __name__ == "__main__":
    main()
