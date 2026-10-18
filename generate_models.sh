#!/bin/bash
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

# Generate the Java models and bundled schemas from the UCP JSON Schemas.
#
# Usage:
#   ./generate_models.sh                 # main branch of the UCP repo
#   ./generate_models.sh 2026-08-25      # a UCP release (branch release/<version>)
#   ./generate_models.sh /path/to/ucp    # a local checkout of the UCP repo
#
# Requires git and python3 (standard library only).

set -euo pipefail
cd "$(dirname "$0")"

WORK_DIR=".ucp"
INPUT="${1:-}"

rm -rf "$WORK_DIR"
if [[ -n "$INPUT" && -d "$INPUT" ]]; then
  echo "Using local UCP checkout $INPUT..."
  mkdir -p "$WORK_DIR"
  cp -R "$INPUT/." "$WORK_DIR/"
  rm -rf "$WORK_DIR/.git"
  VERSION="$(basename "$(cd "$INPUT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo local)")"
else
  if [[ -z "$INPUT" ]]; then
    BRANCH="main"
    VERSION="main"
  else
    BRANCH="release/$INPUT"
    VERSION="$INPUT"
  fi
  echo "Cloning UCP $BRANCH..."
  git clone --quiet --depth 1 -b "$BRANCH" \
    https://github.com/Universal-Commerce-Protocol/ucp "$WORK_DIR"
  rm -rf "$WORK_DIR/.git"
fi

SCHEMA_DIR="$WORK_DIR/source/schemas"
if [[ ! -d "$SCHEMA_DIR" ]]; then
  echo "Error: $SCHEMA_DIR not found; expected a UCP repository layout." >&2
  exit 1
fi

echo "Preprocessing schemas (request variants, allOf flattening)..."
# The preprocessor iterates Python sets when merging `required`; a fixed hash
# seed keeps the bundled schemas byte-identical across runs.
PYTHONHASHSEED=0 python3 codegen/preprocess_schemas.py "$SCHEMA_DIR" > /dev/null

echo "Bundling schemas for the validator..."
RESOURCE_DIR="src/main/resources/dev/ucp/sdk/schemas"
rm -rf "$RESOURCE_DIR"
mkdir -p "$(dirname "$RESOURCE_DIR")"
cp -R "$SCHEMA_DIR" "$RESOURCE_DIR"

echo "Generating Java models..."
rm -rf src/main/java/dev/ucp/sdk/models
python3 codegen/generate_java.py "$SCHEMA_DIR" src/main/java dev.ucp.sdk.models "$VERSION"

echo "Exporting specification examples for the test suite..."
python3 codegen/export_examples.py "$WORK_DIR" "$SCHEMA_DIR" src/test/resources/examples.json

rm -rf "$WORK_DIR"
echo "Done. Run './gradlew build' to compile and test."
