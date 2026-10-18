<!--
   Copyright 2026 UCP Authors

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

<p align="center">
  <h1 align="center">UCP Java SDK</h1>
</p>

<p align="center">
  <b>Java library for the Universal Commerce Protocol (UCP).</b>
</p>

## Overview

This repository contains a Java SDK for the
[Universal Commerce Protocol (UCP)](https://ucp.dev). It provides Jackson
models for every UCP schema and a validator backed by the bundled UCP JSON
Schemas, modeled on the official
[Python](https://github.com/Universal-Commerce-Protocol/python-sdk) and
[JavaScript](https://github.com/Universal-Commerce-Protocol/js-sdk) SDKs.

- **Typed models** — one class per schema, with getters/setters, fluent
  setters, Javadoc from the schema descriptions, and enums for closed value
  sets. Request shapes (`CheckoutCreateRequest`, `CheckoutUpdateRequest`,
  `CheckoutCompleteRequest`, ...) are generated from the spec's
  `ucp_request` annotations, exactly like the other SDKs.
- **Unions** — `oneOf`/`anyOf` schemas become interfaces (`Message` →
  `MessageError` | `MessageWarning` | `MessageInfo`) that deserialize into the
  matching variant, so `instanceof`/pattern matching works.
- **Lossless round trips** — properties a schema does not declare (extension
  fields, newer protocol fields) are kept in `getAdditionalProperties()` and
  written back out.
- **Full-fidelity validation** — the models capture structure and types; the
  validator enforces everything else in the spec (`if`/`then` conditions,
  `contains` counts, `dependentRequired`, patterns, bounds, formats) against
  the schemas bundled in the jar. No network access is needed.

### UCP Version Compatibility

Each version of the Java SDK is generated against a specific version of the
UCP schema:

| SDK Version | UCP Schema Version |
| ----------- | ------------------ |
| **`0.5.x`** | **`2026-08-25`**   |

The version is also available at runtime as `UcpModels.SCHEMA_VERSION`.

## Installation

Requires Java 17 or later.

```xml
<dependency>
  <groupId>dev.ucp</groupId>
  <artifactId>ucp-sdk</artifactId>
  <version>0.5.0-SNAPSHOT</version>
</dependency>
```

Gradle:

```kotlin
implementation("dev.ucp:ucp-sdk:0.5.0-SNAPSHOT")
```

## Usage

### Parsing a response

```java
import dev.ucp.sdk.json.UcpJson;
import dev.ucp.sdk.models.common.types.Message;
import dev.ucp.sdk.models.common.types.MessageError;
import dev.ucp.sdk.models.shopping.Checkout;

Checkout checkout = UcpJson.parse(body, Checkout.class);

checkout.getStatus();     // Checkout.Status.INCOMPLETE, READY_FOR_COMPLETE, ...
checkout.getCurrency();   // ISO 4217 currency code
for (var line : checkout.getLineItems()) {
    System.out.println(line.getItem().getTitle() + ": " + line.getQuantity());
}

for (Message message : checkout.getMessages()) {
    if (message instanceof MessageError error) {
        System.out.println(error.getSeverity() + " at " + error.getPath());
    }
}
```

### Building a request

```java
import dev.ucp.sdk.models.shopping.CheckoutCreateRequest;
import dev.ucp.sdk.models.shopping.types.ItemCreateRequest;
import dev.ucp.sdk.models.shopping.types.LineItemCreateRequest;

CheckoutCreateRequest request = new CheckoutCreateRequest()
        .lineItems(List.of(new LineItemCreateRequest()
                .item(new ItemCreateRequest().id("sku_1"))
                .quantity(2L)));

String json = UcpJson.write(request);
// {"line_items":[{"item":{"id":"sku_1"},"quantity":2}]}
```

### Validation

```java
import dev.ucp.sdk.validation.UcpSchemaValidator;
import dev.ucp.sdk.validation.ValidationResult;

UcpSchemaValidator validator = UcpSchemaValidator.getDefault();

// Validate a model against the schema it was generated from
ValidationResult result = validator.validate(request);
if (!result.isValid()) {
    result.errors().forEach(e ->
            System.out.println(e.instancePath() + " " + e.keyword() + ": " + e.message()));
    // $.line_items[0].quantity minimum: ...
}

// Or validate raw JSON against a schema by location, and throw on failure
validator.validate("shopping/checkout_create_request.json", body).orThrow();
```

### Unknown enum values

Like the Python and JavaScript SDKs, parsing fails on an enum value the schema
does not define. To accept values a peer on a newer UCP release may send, parse
leniently: the field reads as `null` and the raw value is kept in
`getAdditionalProperties()`, so it is written back unchanged and the validator
still reports it.

```java
Checkout checkout = UcpJson.parseLenient(body, Checkout.class);
// or: ObjectMapper mapper = UcpJson.newLenientObjectMapper();
```

### Jackson integration

`UcpJson.mapper()` is the shared, preconfigured `ObjectMapper` (Java time
support, RFC 3339 timestamps, absent values omitted). Use
`UcpJson.newObjectMapper()` to get a copy you can customize or register with a
web framework.

### Available model packages

| Package                               | Description                                                       |
| ------------------------------------- | ----------------------------------------------------------------- |
| `dev.ucp.sdk.models`                  | UCP metadata, profile, services, capabilities, payment handlers   |
| `dev.ucp.sdk.models.shopping`         | Checkout, cart, catalog, order, discount, fulfillment, permalink  |
| `dev.ucp.sdk.models.shopping.types`   | Line items, items, adjustments, fulfillments, destinations        |
| `dev.ucp.sdk.models.common`           | Location search/lookup, identity linking, loyalty, payment terms  |
| `dev.ucp.sdk.models.common.types`     | Shared primitives (totals, prices, messages, postal address, ...) |
| `dev.ucp.sdk.models.transports`       | JSON-RPC, MCP, A2A, and embedded protocol bindings                |

Schemas that only hold `$defs` (for example `catalog_search.json`) map to a
holder class with the definitions nested inside it:
`CatalogSearch.SearchRequest`, `CatalogSearch.SearchResponse`.
`UcpModels.forSchema("shopping/checkout.json")` looks a model up by schema
location.

## Development

### Prerequisites

Java 17+ (the build itself runs on JDK 21+; CI uses JDK 25), Python 3.10+ (standard library
only) and git. Gradle is provided by the wrapper (`./gradlew`).

### Generating models

The models and the bundled schemas are generated from the JSON schemas in the
[UCP specification](https://github.com/Universal-Commerce-Protocol/ucp):

```bash
./generate_models.sh 2026-08-25     # a UCP release
./generate_models.sh                # the main branch
./generate_models.sh /path/to/ucp   # a local checkout
```

The pipeline:

1. `codegen/preprocess_schemas.py` — the Python SDK's preprocessor (one
   documented change) materializes the `*_create_request` /
   `*_update_request` / `*_complete_request` variants and flattens `allOf`.
2. `codegen/generate_java.py` — emits the Jackson models and `UcpModels`.
3. The preprocessed schemas are bundled under
   `src/main/resources/dev/ucp/sdk/schemas` for the validator.
4. `codegen/export_examples.py` — exports every annotated JSON example in the
   spec docs, using the spec's own example reducer, as the test corpus.

### Running tests

```bash
./gradlew spotlessCheck                        # formatting (./gradlew spotlessApply to fix)
./gradlew test                                 # unit tests
./gradlew integrationTest                      # every generated model and spec example
./gradlew build                                # all of the above, plus jars and Javadoc
python3 -m unittest discover -s codegen/tests  # code generator
```

The integration tests (`*IT`) run every documentation example of the pinned
UCP release through the SDK — each must map to a model, survive a
parse/serialize round trip unchanged, and validate against the bundled
schemas — and check the contract of every generated type. Generated models are
excluded from Spotless; the CI model-drift job keeps them in sync instead.

## License

UCP is an open-source project under the [Apache License 2.0](LICENSE).
