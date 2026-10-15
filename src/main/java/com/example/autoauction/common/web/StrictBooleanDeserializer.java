package com.example.autoauction.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * O1: accepts only the JSON literals {@code true}/{@code false}. Jackson's default coercion would
 * read {@code "true"}, {@code 1} or {@code "1"} as {@code true}; for an explicit confirmation that is
 * not good enough. Anything that is not a JSON boolean is read as absent ({@code null}), so the
 * field's own {@code @NotNull} refuses it with an ordinary 400 - no change to global Jackson config.
 */
public final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            parser.skipChildren();
        }
        return null;
    }
}
