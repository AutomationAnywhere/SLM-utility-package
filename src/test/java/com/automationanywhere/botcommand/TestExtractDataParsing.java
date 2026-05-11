package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;

import static org.testng.Assert.*;

/**
 * Unit tests for ExtractData.parseExtractionResponse().
 * No model download required.
 */
public class TestExtractDataParsing {

    private ExtractData action;

    @BeforeClass
    public void setUp() {
        action = new ExtractData();
    }

    // ── All fields found ──────────────────────────────────────────────────────

    @Test
    public void testAllFieldsFound() {
        String fields = "invoice_number: the invoice reference\ntotal_amount: the total due";
        String response = "invoice_number: INV-1234\ntotal_amount: $450.00";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("invoice_number")).get(), "INV-1234");
        assertEquals(((StringValue) result.get("total_amount")).get(), "$450.00");
    }

    @Test
    public void testSingleFieldFound() {
        String fields = "vendor_name: the vendor or supplier name";
        String response = "vendor_name: Acme Corp";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("vendor_name")).get(), "Acme Corp");
    }

    // ── NOT_FOUND handling ────────────────────────────────────────────────────

    @Test
    public void testNotFoundReturned() {
        String fields = "ssn: social security number";
        String response = "ssn: NOT_FOUND";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("ssn")).get(), ExtractData.NOT_FOUND);
    }

    @Test
    public void testMissingFieldDefaultsToNotFound() {
        String fields = "invoice_number: invoice ID\ntotal_amount: total due";
        String response = "invoice_number: INV-9999";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("invoice_number")).get(), "INV-9999");
        assertEquals(((StringValue) result.get("total_amount")).get(), ExtractData.NOT_FOUND);
    }

    @Test
    public void testEmptyValueDefaultsToNotFound() {
        String fields = "due_date: payment due date";
        String response = "due_date:";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("due_date")).get(), ExtractData.NOT_FOUND);
    }

    // ── Null / empty response ─────────────────────────────────────────────────

    @Test
    public void testNullResponse_allFieldsSetToNotFound() {
        String fields = "field1: first field\nfield2: second field";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(null, fields);

        assertEquals(((StringValue) result.get("field1")).get(), ExtractData.NOT_FOUND);
        assertEquals(((StringValue) result.get("field2")).get(), ExtractData.NOT_FOUND);
    }

    @Test
    public void testEmptyResponse_allFieldsSetToNotFound() {
        String fields = "name: person name";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse("", fields);

        assertEquals(((StringValue) result.get("name")).get(), ExtractData.NOT_FOUND);
    }

    // ── Case-insensitive key matching ─────────────────────────────────────────

    @Test
    public void testCaseInsensitiveMatch() {
        String fields = "InvoiceNumber: invoice reference";
        String response = "invoicenumber: INV-0001";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("InvoiceNumber")).get(), "INV-0001");
    }

    // ── Value contains colons ─────────────────────────────────────────────────

    @Test
    public void testValueWithColonPreserved() {
        String fields = "time: meeting time";
        String response = "time: 10:30 AM";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("time")).get(), "10:30 AM");
    }

    // ── Input order preserved ─────────────────────────────────────────────────

    @Test
    public void testFieldOrderPreserved() {
        String fields = "first: first field\nsecond: second field\nthird: third field";
        String response = "third: C\nfirst: A\nsecond: B";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        String[] keys = result.keySet().toArray(new String[0]);
        assertEquals(keys[0], "first");
        assertEquals(keys[1], "second");
        assertEquals(keys[2], "third");
    }

    // ── DeepSeek R1 thinking blocks ───────────────────────────────────────────

    @Test
    public void testThinkingBlockStripped() {
        String fields = "name: person name";
        String response = "<think>The name appears to be John Smith.</think>name: John Smith";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("name")).get(), "John Smith");
    }

    // ── Fields spec with blank lines ──────────────────────────────────────────

    @Test
    public void testBlankLinesInFieldsIgnored() {
        String fields = "field_a: first\n\nfield_b: second\n";
        String response = "field_a: alpha\nfield_b: beta";

        LinkedHashMap<String, Value<?>> result = action.parseExtractionResponse(response, fields);

        assertEquals(((StringValue) result.get("field_a")).get(), "alpha");
        assertEquals(((StringValue) result.get("field_b")).get(), "beta");
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    public void testNotFoundConstantValue() {
        assertEquals(ExtractData.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    public void testMaxOutputTokensPositive() {
        assertTrue(ExtractData.MAX_OUTPUT_TOKENS > 0);
    }
}
