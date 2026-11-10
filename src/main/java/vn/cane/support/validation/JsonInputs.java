package vn.cane.support.validation;

import java.math.BigInteger;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Structural checks for dynamic JSON inputs; errors never echo unknown property names. */
public final class JsonInputs {
    private JsonInputs() {}

    public static ObjectNode object(JsonNode value, Set<String> allowed, String field) {
        if (!(value instanceof ObjectNode object) || !allowed.containsAll(object.propertyNames())) {
            throw InputValidationException.malformed(field);
        }
        return object;
    }

    public static ObjectNode completeObject(JsonNode value, Set<String> properties, String field) {
        ObjectNode object = object(value, properties, field);
        for (String property : properties) {
            required(object, property, field + "." + property);
        }
        return object;
    }

    public static JsonNode required(ObjectNode object, String key, String field) {
        JsonNode value = object.get(key);
        if (value == null) {
            throw InputValidationException.malformed(field);
        }
        return value;
    }

    public static String text(JsonNode value, String field) {
        if (value == null || !value.isString()) {
            throw InputValidationException.malformed(field);
        }
        return value.asString();
    }

    public static boolean bool(JsonNode value, String field) {
        if (value == null || !value.isBoolean()) {
            throw InputValidationException.malformed(field);
        }
        return value.booleanValue();
    }

    public static BigInteger integer(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber()) {
            throw InputValidationException.malformed(field);
        }
        return value.bigIntegerValue();
    }

    public static ArrayNode array(JsonNode value, String field) {
        if (!(value instanceof ArrayNode array)) {
            throw InputValidationException.malformed(field);
        }
        return array;
    }
}
