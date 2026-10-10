package com.zqnt.sdk.client.commands.domains;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command params and results as plain Java values: maps, lists, strings, numbers, booleans. Numbers
 * come back as {@link Double}, as in any JSON object.
 */
public final class Structs {

    private Structs() {
    }

    /** {@code params} as a Struct; a top-level {@code null} value is left out. */
    public static Struct toStruct(Map<String, ?> params) {
        Struct.Builder struct = Struct.newBuilder();
        if (params == null) return struct.build();
        params.forEach((key, value) -> {
            if (value != null) struct.putFields(key, toValue(value));
        });
        return struct.build();
    }

    public static Map<String, Object> toMap(Struct struct) {
        Map<String, Object> map = new LinkedHashMap<>();
        struct.getFieldsMap().forEach((key, value) -> map.put(key, fromValue(value)));
        return map;
    }

    public static Value toValue(Object value) {
        if (value == null) return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        if (value instanceof Value proto) return proto;
        if (value instanceof Struct struct) return Value.newBuilder().setStructValue(struct).build();
        if (value instanceof Boolean bool) return Value.newBuilder().setBoolValue(bool).build();
        if (value instanceof Number number) return Value.newBuilder().setNumberValue(number.doubleValue()).build();
        if (value instanceof CharSequence || value instanceof Character) {
            return Value.newBuilder().setStringValue(value.toString()).build();
        }
        if (value instanceof Enum<?> constant) return Value.newBuilder().setStringValue(constant.name()).build();
        if (value instanceof Map<?, ?> map) {
            Struct.Builder struct = Struct.newBuilder();
            map.forEach((key, nested) -> struct.putFields(String.valueOf(key), toValue(nested)));
            return Value.newBuilder().setStructValue(struct).build();
        }
        if (value instanceof Iterable<?> items) {
            ListValue.Builder list = ListValue.newBuilder();
            items.forEach(item -> list.addValues(toValue(item)));
            return Value.newBuilder().setListValue(list).build();
        }
        if (value instanceof Object[] items) {
            ListValue.Builder list = ListValue.newBuilder();
            for (Object item : items) list.addValues(toValue(item));
            return Value.newBuilder().setListValue(list).build();
        }
        throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
    }

    public static Object fromValue(Value value) {
        return switch (value.getKindCase()) {
            case BOOL_VALUE -> value.getBoolValue();
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case STRUCT_VALUE -> toMap(value.getStructValue());
            case LIST_VALUE -> {
                List<Object> list = new ArrayList<>();
                value.getListValue().getValuesList().forEach(item -> list.add(fromValue(item)));
                yield list;
            }
            case NULL_VALUE, KIND_NOT_SET -> null;
        };
    }
}
