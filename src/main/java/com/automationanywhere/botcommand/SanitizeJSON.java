package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.LlamaInference;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.AttributeType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.automationanywhere.commandsdk.model.DataType.STRING;

/**
 * SanitizeJSON Action
 *
 * Escapes special characters in a string so it is safe to embed inside a JSON value.
 * Uses deterministic rule-based escaping — no model download required, no cloud calls.
 *
 * Characters escaped: backslash, double-quote, newline, carriage return, tab,
 * and other JSON control characters.
 *
 * Use this before constructing JSON manually or before passing text into a
 * JSON template where the value field is not already handled by a JSON library.
 */
@BotCommand
@CommandPkg(
    label = "Sanitize JSON Text",
    name = "sanitizeJSON",
    description = "Escapes special characters in text to make it safe for embedding in JSON values",
    node_label = "Sanitize JSON: {{inputText}}",
    icon = "pkg.svg",
    comment = true,
    text_color = "#2C3E50",
    background_color = "#ECF0F1",
    allowed_agent_targets = {
            AllowedTarget.WINDOWS,
            AllowedTarget.MAC_OS
    },
    return_type = STRING,
    return_required = true
)
public class SanitizeJSON {

    private static final Logger logger = LogManager.getLogger(SanitizeJSON.class);

    @Execute
    public Value<String> execute(

        @Idx(index = "1", type = AttributeType.TEXT)
        @Pkg(label = "Input Text", description = "Text to sanitize for use as a JSON string value")
        @NotEmpty
        String inputText

    ) {
        if (inputText == null || inputText.trim().isEmpty()) {
            throw new BotCommandException("Input text cannot be empty");
        }

        String sanitized = LlamaInference.sanitizeForJSON(inputText);
        logger.debug("SanitizeJSON: {} chars → {} chars", inputText.length(), sanitized.length());
        return new StringValue(sanitized);
    }
}
