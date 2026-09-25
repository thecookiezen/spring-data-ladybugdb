package com.thecookiezen.ladybugdb.spring.mapper;

import com.ladybugdb.DataTypeID;
import com.ladybugdb.LbugList;
import com.ladybugdb.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Utility class for mapping LadybugDB Value objects to Java types.
 * Optimized to use direct Native-to-Java conversion via Value.getValue().
 */
public final class ValueMappers {

    private ValueMappers() {
        // Utility class
    }

    /**
     * Maps a Value to a String.
     *
     * @param value the LadybugDB Value
     * @return the string representation
     */
    public static String asString(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.getValue();
    }

    /**
     * Maps a Value to an Integer.
     *
     * @param value the LadybugDB Value
     * @return the integer value, or null if the value is null
     */
    public static Integer asInteger(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.getValue();
        if (raw instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(raw.toString());
    }

    /**
     * Maps a Value to a Long.
     *
     * @param value the LadybugDB Value
     * @return the long value, or null if the value is null
     */
    public static Long asLong(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.getValue();
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(raw.toString());
    }

    /**
     * Maps a Value to a Double.
     *
     * @param value the LadybugDB Value
     * @return the double value, or null if the value is null
     */
    public static Double asDouble(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.getValue();
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(raw.toString());
    }

    /**
     * Maps a Value to a Boolean.
     *
     * @param value the LadybugDB Value
     * @return the boolean value, or null if the value is null
     */
    public static Boolean asBoolean(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.getValue();
    }

    /**
     * Maps a Value containing a list to a List of the specified type.
     * Bypasses string conversion where possible.
     */
    public static <T> List<T> asList(Value value, Function<Value, T> elementMapper) {
        if (value == null || value.isNull()) {
            return List.of();
        }

        long size = listSize(value);
        List<T> result = new ArrayList<>((int) size);

        try (LbugList lbugList = new LbugList(value)) {
            for (long i = 0; i < size; i++) {
                try (Value element = lbugList.getListElement(i)) {
                    result.add(elementMapper.apply(element));
                }
            }
        }

        return result;
    }

    public static List<String> asStringList(Value value) {
        return asList(value, Value::getValue);
    }

    public static List<Integer> asIntegerList(Value value) {
        return asList(value, ValueMappers::asInteger);
    }

    public static List<Long> asLongList(Value value) {
        return asList(value, ValueMappers::asLong);
    }

    public static List<Double> asDoubleList(Value value) {
        return asList(value, ValueMappers::asDouble);
    }

    public static List<Boolean> asBooleanList(Value value) {
        return asList(value, ValueMappers::asBoolean);
    }

    /**
     * Maps a Value containing a list of floats to a List of Float.
     *
     * @param value the LadybugDB Value
     * @return the list of floats, or an empty list if the value is null
     */
    public static List<Float> asFloatList(Value value) {
        return asList(value, ValueMappers::asFloat);
    }

    private static Float asFloat(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.getValue();
        if (raw instanceof Number n) {
            return n.floatValue();
        }
        return Float.parseFloat(raw.toString());
    }

    public static float[] asFloatArray(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }

        long size = listSize(value);
        float[] result = new float[(int) size];

        try (LbugList lbugList = new LbugList(value)) {
            for (long i = 0; i < size; i++) {
                try (Value element = lbugList.getListElement(i)) {
                    result[(int) i] = asFloat(element);
                }
            }
        }

        return result;
    }

    /**
     * Returns the number of elements of a list or fixed-size array value.
     * <p>
     * Fixed-size array values (e.g. {@code FLOAT[4]} vector columns, reported as
     * {@link DataTypeID#ARRAY}) do not support {@link LbugList#getListSize()};
     * their size is carried by the data type instead.
     */
    private static long listSize(Value value) {
        if (value.getDataType().getID() == DataTypeID.ARRAY) {
            return value.getDataType().getFixedNumElementsInList();
        }
        try (LbugList lbugList = new LbugList(value)) {
            return lbugList.getListSize();
        }
    }
}