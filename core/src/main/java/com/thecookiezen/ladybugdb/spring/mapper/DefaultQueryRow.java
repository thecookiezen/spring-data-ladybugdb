package com.thecookiezen.ladybugdb.spring.mapper;

import com.ladybugdb.DataTypeID;
import com.ladybugdb.FlatTuple;
import com.ladybugdb.LbugStruct;
import com.ladybugdb.Value;
import com.ladybugdb.ValueRelUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link QueryRow} that wraps the raw column-to-value
 * map from a query result.
 * <p>
 * Uses {@link LbugStruct} to extract node properties and {@link ValueRelUtil}
 * to extract relationship data.
 * <p>
 * The row owns its values: {@link #bind(FlatTuple)} releases the values of the
 * previous row and takes over the values of the new one, and {@link #close()}
 * releases everything held by the row.
 */
public final class DefaultQueryRow implements QueryRow {

    private final Value[] values;
    private final Map<String, Integer> columnToIndex;
    private final List<LbugStruct> openStructs = new ArrayList<>();

    /**
     * Creates an empty QueryRow sized to the given column map.
     *
     * @param columnToIndex the shared map of column names to indices
     */
    public DefaultQueryRow(Map<String, Integer> columnToIndex) {
        this.columnToIndex = columnToIndex;
        this.values = new Value[columnToIndex.size()];
    }

    /**
     * Binds the values of the given tuple to this row. Any values bound
     * previously are released first.
     *
     * @param tuple the tuple holding the values of the current result row
     */
    public void bind(FlatTuple tuple) {
        closeValues();
        for (int i = 0; i < values.length; i++) {
            values[i] = tuple.getValue(i);
        }
    }

    @Override
    public Value getValue(int index) {
        return index >= 0 && index < values.length ? values[index] : null;
    }

    @Override
    public Value getValue(String column) {
        Integer index = columnToIndex.get(column);
        return index != null && index < values.length ? values[index] : null;
    }

    @Override
    public boolean containsKey(String column) {
        return columnToIndex.containsKey(column);
    }

    @Override
    public boolean isNode(String column) {
        Value value = getValue(column);
        return value != null && value.getDataType().getID() == DataTypeID.NODE;
    }

    @Override
    public boolean isRelationship(String column) {
        Value value = getValue(column);
        return value != null && value.getDataType().getID() == DataTypeID.REL;
    }

    @Override
    public Map<String, Value> getNode(String column) {
        Value value = getValue(column);
        if (value == null) {
            throw new IllegalArgumentException("Column '" + column + "' does not exist");
        }
        if (value.getDataType().getID() != DataTypeID.NODE) {
            throw new IllegalArgumentException(
                    "Column '" + column + "' is not a NODE (type: " + value.getDataType().getID() + ")");
        }
        // The struct wrapper clones the node value, and closing it would free the
        // child values handed out in the returned map. Keep it open for the row's
        // lifetime; it is released by close(), which the template invokes after
        // the row mapper has finished.
        LbugStruct struct = new LbugStruct(value);
        openStructs.add(struct);
        int numFields = Long.valueOf(struct.getNumFields()).intValue();
        Map<String, Value> ret = new HashMap<>(numFields);
        for (int i = 0; i < numFields; ++i) {
            ret.put(struct.getFieldNameByIndex((long) i), struct.getValueByIndex((long) i));
        }
        return ret;
    }

    @Override
    public RelationshipData getRelationship(String column) {
        Value value = getValue(column);
        if (value == null) {
            throw new IllegalArgumentException("Column '" + column + "' does not exist");
        }
        if (value.getDataType().getID() != DataTypeID.REL) {
            throw new IllegalArgumentException(
                    "Column '" + column + "' is not a REL (type: " + value.getDataType().getID() + ")");
        }

        var id = ValueRelUtil.getID(value);
        var labelName = ValueRelUtil.getLabelName(value);
        var sourceId = ValueRelUtil.getSrcID(value);
        var targetId = ValueRelUtil.getDstID(value);

        long propertySize = ValueRelUtil.getPropertySize(value);
        Map<String, Value> properties = new HashMap<>();
        for (long i = 0; i < propertySize; i++) {
            String propName = ValueRelUtil.getPropertyNameAt(value, i);
            Value propValue = ValueRelUtil.getPropertyValueAt(value, i);
            properties.put(propName, propValue);
        }

        return new RelationshipData(id, labelName, sourceId, targetId, properties);
    }

    @Override
    public Set<String> keySet() {
        return columnToIndex.keySet();
    }

    @Override
    public void close() {
        for (LbugStruct struct : openStructs) {
            struct.close();
        }
        openStructs.clear();
        closeValues();
    }

    private void closeValues() {
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                values[i].close();
                values[i] = null;
            }
        }
    }
}
