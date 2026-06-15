package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SkillReader#parse} splits a skill {@code .md} into a {@link SkillSpec}: an optional leading
 * {@code ---}-fenced JSON front-matter ({@code name}/{@code description}/{@code inputSchema}) + the
 * template, validating the front-matter is a JSON object and the {@code inputSchema} (if present) is a
 * well-formed v0.5-subset schema. A plain unit test — pure parsing + validation, no I/O.
 */
class SkillReaderParseTest {

    @Test
    void aSkillWithNoFrontMatterIsValidAndIsAllTemplate() {
        SkillSpec spec = SkillReader.parse("summarize", "Summarize the following: {{input}}");

        assertTrue(spec.name().isEmpty(), "no front-matter → no declared name");
        assertTrue(spec.description().isEmpty());
        assertTrue(spec.inputSchema().isEmpty(), "no front-matter → no declared input schema");
        assertEquals("Summarize the following: {{input}}", spec.template());
    }

    @Test
    void frontMatterIsParsedAndTheTemplateIsTheRemainder() {
        SkillSpec spec = SkillReader.parse("summarize", """
                ---
                { "name": "summarize", "description": "Summarize text",
                  "inputSchema": { "type": "object", "required": ["text"],
                    "properties": { "text": { "type": "string" } } } }
                ---
                Summarize the following: {{text}}
                """);

        assertEquals("summarize", spec.name().orElseThrow());
        assertEquals("Summarize text", spec.description().orElseThrow());
        assertTrue(spec.inputSchema().orElseThrow().contains("\"text\""),
                "the declared input schema is captured as a compact JSON string");
        assertEquals("Summarize the following: {{text}}", spec.template());
    }

    @Test
    void aSchemaLessFrontMatterIsAllowed() {
        SkillSpec spec = SkillReader.parse("greet", """
                ---
                { "name": "greet", "description": "Say hello" }
                ---
                Hello, {{who}}!
                """);

        assertEquals("greet", spec.name().orElseThrow());
        assertTrue(spec.inputSchema().isEmpty(), "inputSchema is optional");
        assertEquals("Hello, {{who}}!", spec.template());
    }

    @Test
    void malformedFrontMatterJsonIsRejected() {
        SkillSpecException e = assertThrows(SkillSpecException.class, () -> SkillReader.parse("bad", """
                ---
                { "name": not-json }
                ---
                body
                """));
        assertTrue(e.getMessage().contains("not valid JSON"));
    }

    @Test
    void nonObjectFrontMatterIsRejected() {
        assertThrows(SkillSpecException.class, () -> SkillReader.parse("arr", """
                ---
                [1, 2, 3]
                ---
                body
                """));
    }

    @Test
    void anUnclosedFrontMatterIsRejected() {
        SkillSpecException e = assertThrows(SkillSpecException.class, () -> SkillReader.parse("open", """
                ---
                { "name": "x" }
                body with no closing fence
                """));
        assertTrue(e.getMessage().contains("never closed"));
    }

    @Test
    void anInvalidInputSchemaIsRejectedOnRead() {
        // 'required' must be an array (the OutputSchemaValidator v0.5-subset, reused here) — a malformed
        // declared schema is caught at parse time, not at some later invocation.
        SkillSpecException e = assertThrows(SkillSpecException.class, () -> SkillReader.parse("badschema", """
                ---
                { "name": "x", "inputSchema": { "type": "object", "required": "text" } }
                ---
                body
                """));
        assertTrue(e.getMessage().contains("required"), "the failure names the malformed schema part");
    }

    @Test
    void leadingBlankLinesBeforeTheFenceStillParse() {
        SkillSpec spec = SkillReader.parse("padded", "\n\n---\n{ \"name\": \"x\" }\n---\nbody");
        assertEquals("x", spec.name().orElseThrow());
        assertEquals("body", spec.template());
    }
}
