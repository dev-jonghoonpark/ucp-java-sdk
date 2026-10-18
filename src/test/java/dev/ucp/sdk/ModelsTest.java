/*
 * Copyright 2026 UCP Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ucp.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ucp.sdk.json.UcpJson;
import dev.ucp.sdk.models.Ucp;
import dev.ucp.sdk.models.UcpModels;
import dev.ucp.sdk.models.common.types.ConstraintExpression;
import dev.ucp.sdk.models.common.types.Message;
import dev.ucp.sdk.models.common.types.MessageError;
import dev.ucp.sdk.models.common.types.MessageInfo;
import dev.ucp.sdk.models.shopping.CatalogSearch;
import dev.ucp.sdk.models.shopping.Checkout;
import dev.ucp.sdk.models.shopping.CheckoutCreateRequest;
import dev.ucp.sdk.models.shopping.types.FulfillmentDestination;
import dev.ucp.sdk.models.shopping.types.ItemCreateRequest;
import dev.ucp.sdk.models.shopping.types.LineItemCreateRequest;
import dev.ucp.sdk.models.shopping.types.ShippingDestination;
import dev.ucp.sdk.validation.UcpSchemaValidator;
import dev.ucp.sdk.validation.UcpValidationException;
import dev.ucp.sdk.validation.ValidationResult;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelsTest {
    private static final UcpSchemaValidator VALIDATOR = UcpSchemaValidator.getDefault();

    private static final String CHECKOUT = """
            {
              "ucp": {
                "version": "2026-08-25",
                "status": "success",
                "capabilities": {
                  "dev.ucp.shopping.checkout": [{"version": "2026-08-25"}]
                },
                "payment_handlers": {}
              },
              "id": "chk_123",
              "status": "incomplete",
              "currency": "USD",
              "line_items": [
                {
                  "id": "li_1",
                  "item": {"id": "sku_1", "title": "Blue T-Shirt", "price": 2500},
                  "quantity": 2,
                  "totals": [
                    {"type": "subtotal", "amount": 5000},
                    {"type": "total", "amount": 5000}
                  ]
                }
              ],
              "totals": [
                {"type": "subtotal", "amount": 5000},
                {"type": "tax", "amount": 400},
                {"type": "total", "amount": 5400}
              ],
              "messages": [
                {"type": "error", "code": "missing", "content": "Shipping address required",
                 "severity": "recoverable", "path": "$.buyer"},
                {"type": "info", "content": "Free shipping over $50"}
              ],
              "links": [{"type": "terms_of_service", "url": "https://example.com/terms"}],
              "expires_at": "2026-10-18T12:00:00Z",
              "com.example.loyalty_tier": "gold"
            }
            """;

    @Test
    void parsesCheckoutIntoTypedModel() {
        Checkout checkout = UcpJson.parse(CHECKOUT, Checkout.class);

        assertEquals("chk_123", checkout.getId());
        assertEquals(Checkout.Status.INCOMPLETE, checkout.getStatus());
        assertEquals("2026-08-25", checkout.getUcp().getVersion());
        assertEquals(2L, checkout.getLineItems().get(0).getQuantity());
        assertEquals("Blue T-Shirt", checkout.getLineItems().get(0).getItem().getTitle());
        assertEquals(5400L, checkout.getTotals().get(2).getAmount());
        assertEquals(OffsetDateTime.parse("2026-10-18T12:00:00Z"), checkout.getExpiresAt());
        assertEquals("https", checkout.getLinks().get(0).getUrl().getScheme());
    }

    @Test
    void unionResolvesToVariantByDiscriminator() {
        List<Message> messages = UcpJson.parse(CHECKOUT, Checkout.class).getMessages();

        MessageError error = assertInstanceOf(MessageError.class, messages.get(0));
        assertEquals(MessageError.Severity.RECOVERABLE, error.getSeverity());
        assertEquals("$.buyer", error.getPath());
        assertInstanceOf(MessageInfo.class, messages.get(1));
    }

    @Test
    void unionRejectsClosedVariantWhenInputHasUndeclaredProperties() {
        // ConstraintExpression is closed and has no "enum"; ValueConstraint declares it.
        ConstraintExpression constraint = UcpJson.parse(
                "{\"properties\": {\"brand\": {\"enum\": [\"visa\", \"mastercard\"]}}}",
                ConstraintExpression.class);

        assertInstanceOf(ConstraintExpression.ValueConstraint.class,
                constraint.getProperties().get("brand"));
    }

    @Test
    void undeclaredPropertiesSurviveRoundTrip() {
        Checkout checkout = UcpJson.parse(CHECKOUT, Checkout.class);

        assertEquals("gold", checkout.getAdditionalProperties().get("com.example.loyalty_tier"));
        JsonNode written = UcpJson.toTree(checkout);
        assertEquals("gold", written.get("com.example.loyalty_tier").asText());
        assertFalse(written.has("buyer"), "absent fields must not be written as null");
        assertEquals("2026-10-18T12:00:00Z", written.get("expires_at").asText());
    }

    @Test
    void checkoutValidatesAgainstItsSchema() {
        Checkout checkout = UcpJson.parse(CHECKOUT, Checkout.class);

        ValidationResult result = VALIDATOR.validate(checkout);

        assertTrue(result.isValid(), () -> result.errors().toString());
        assertEquals("shopping/checkout.json", result.schema());
    }

    @Test
    void validatorEnforcesRulesTheTypesCannotExpress() {
        // totals must contain exactly one subtotal: a `contains` rule.
        Checkout checkout = UcpJson.parse(CHECKOUT, Checkout.class);
        checkout.getTotals().remove(0);

        ValidationResult result = VALIDATOR.validate(checkout);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.instancePath().equals("$.totals")),
                () -> result.errors().toString());
    }

    @Test
    void buildsCreateRequestFluently() {
        CheckoutCreateRequest request = new CheckoutCreateRequest()
                .lineItems(List.of(new LineItemCreateRequest()
                        .item(new ItemCreateRequest().id("sku_1"))
                        .quantity(2L)));

        assertEquals(
                "{\"line_items\":[{\"item\":{\"id\":\"sku_1\"},\"quantity\":2}]}",
                UcpJson.write(request));
        assertTrue(VALIDATOR.validate(request).isValid());
    }

    @Test
    void invalidRequestReportsPathAndKeyword() {
        CheckoutCreateRequest request = new CheckoutCreateRequest()
                .lineItems(List.of(new LineItemCreateRequest()
                        .item(new ItemCreateRequest().id("sku_1"))
                        .quantity(0L)));

        ValidationResult result = VALIDATOR.validate(request);

        assertFalse(result.isValid());
        assertEquals("$.line_items[0].quantity", result.errors().get(0).instancePath());
        assertEquals("minimum", result.errors().get(0).keyword());
        UcpValidationException e = assertThrows(UcpValidationException.class, result::orThrow);
        assertTrue(e.getMessage().contains("shopping/checkout_create_request.json"));
    }

    @Test
    void validatesRawJsonAgainstNamedSchema() {
        ValidationResult missingLineItems =
                VALIDATOR.validate("shopping/checkout_create_request.json", "{}");
        assertFalse(missingLineItems.isValid());
        assertEquals("required", missingLineItems.errors().get(0).keyword());

        assertTrue(VALIDATOR.validate(CatalogSearch.SearchRequest.class,
                "{\"query\": \"running shoes\"}").isValid());
    }

    @Test
    void openSchemasConvertToTheirConcreteShape() {
        FulfillmentDestination destination = UcpJson.parse("""
                {"type": "shipping_address", "id": "dest_1",
                 "street_address": "1600 Amphitheatre Pkwy", "address_country": "US"}
                """, FulfillmentDestination.class);

        ShippingDestination shipping = UcpJson.convert(destination, ShippingDestination.class);

        assertEquals("1600 Amphitheatre Pkwy", shipping.getStreetAddress());
        assertEquals("US", shipping.getAddressCountry());
    }

    @Test
    void registryFindsModelsBySchemaLocation() {
        assertEquals(Checkout.class, UcpModels.forSchema("shopping/checkout.json").orElseThrow());
        assertEquals(Ucp.ResponseCheckoutSchema.class,
                UcpModels.forSchema("ucp.json#/$defs/response_checkout_schema").orElseThrow());
        assertEquals("shopping/catalog_search.json#/$defs/search_request",
                UcpSchemaValidator.schemaOf(CatalogSearch.SearchRequest.class));
    }

    @Test
    void unknownEnumValueFailsParsing() {
        assertThrows(UncheckedIOException.class, () -> UcpJson.parse(
                CHECKOUT.replace("\"incomplete\"", "\"bogus\""), Checkout.class));
    }
}
