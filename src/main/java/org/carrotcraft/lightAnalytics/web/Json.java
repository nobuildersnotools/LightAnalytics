package org.carrotcraft.lightAnalytics.web;

/**
 * A minimal, dependency-free JSON writer — enough to serialise the flat metric
 * records the dashboard API returns, in the same hand-rolled spirit as the
 * plugin's TOML config parser. It does not parse JSON and is not a general
 * serialiser; callers build objects and arrays explicitly.
 *
 * <p>Non-finite doubles (NaN/Infinity), which have no JSON representation, are
 * written as {@code null}.
 */
public final class Json {

    private Json() {
    }

    /** Starts a JSON object. */
    public static JsonObject object() {
        return new JsonObject();
    }

    /** Starts a JSON array. */
    public static JsonArray array() {
        return new JsonArray();
    }

    /** A JSON object builder; {@link #build()} closes it and returns the text. */
    public static final class JsonObject {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private JsonObject key(String name) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(name)).append(':');
            return this;
        }

        public JsonObject add(String name, String value) {
            key(name).sb.append(value == null ? "null" : quote(value));
            return this;
        }

        public JsonObject add(String name, long value) {
            key(name).sb.append(value);
            return this;
        }

        public JsonObject add(String name, int value) {
            key(name).sb.append(value);
            return this;
        }

        public JsonObject add(String name, double value) {
            key(name).sb.append(number(value));
            return this;
        }

        public JsonObject add(String name, boolean value) {
            key(name).sb.append(value);
            return this;
        }

        /** Adds a pre-rendered JSON value (object or array) under {@code name}. */
        public JsonObject addRaw(String name, String rawJson) {
            key(name).sb.append(rawJson);
            return this;
        }

        public String build() {
            return sb.append('}').toString();
        }
    }

    /** A JSON array builder; {@link #build()} closes it and returns the text. */
    public static final class JsonArray {
        private final StringBuilder sb = new StringBuilder("[");
        private boolean first = true;

        private JsonArray sep() {
            if (!first) {
                sb.append(',');
            }
            first = false;
            return this;
        }

        /** Adds a pre-rendered JSON value (typically an object). */
        public JsonArray addRaw(String rawJson) {
            sep().sb.append(rawJson);
            return this;
        }

        public String build() {
            return sb.append(']').toString();
        }
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
