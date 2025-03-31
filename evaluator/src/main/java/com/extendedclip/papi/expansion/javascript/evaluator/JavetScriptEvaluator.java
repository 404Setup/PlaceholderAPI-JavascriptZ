package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.*;
import com.caoccao.javet.values.reference.V8ValueArray;
import com.caoccao.javet.values.reference.V8ValueObject;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class JavetScriptEvaluator implements ScriptEvaluator, Closeable {

    private final V8Runtime v8Runtime;
    private final Map<String, Object> bindings;
    private final List<V8Value> managedV8Values;

    public JavetScriptEvaluator(final V8Runtime v8Runtime, final Map<String, Object> bindings) {
        this.v8Runtime = v8Runtime;
        this.bindings = bindings != null ? new HashMap<>(bindings) : new HashMap<>();
        this.managedV8Values = new ArrayList<>();
    }

    protected <T extends V8Value> T trackV8Value(T value) {
        if (value != null) {
            managedV8Values.add(value);
        }
        return value;
    }

    @Override
    public Object execute(final Map<String, Object> additionalBindings, final String script)
            throws EvaluatorException {
        if (v8Runtime == null || v8Runtime.isClosed()) {
            throw new EvaluatorException("JavaScript runtime is not available");
        }

        List<V8Value> tempV8Values = new ArrayList<>();

        try {
            applyBindings(bindings);

            if (additionalBindings != null && !additionalBindings.isEmpty()) {
                applyBindings(additionalBindings);
            }

            Object result = v8Runtime.getExecutor(script).execute();

            if (result instanceof V8Value v8Result) {
                tempV8Values.add(v8Result);
                result = convertToJavaObject(result);
            }

            return result;
        } catch (JavetException e) {
            throw new EvaluatorException("Script execution failed: " + e.getMessage(), e);
        } finally {
            closeV8Values(tempV8Values);
        }
    }

    private void closeV8Values(List<V8Value> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        for (V8Value value : values) {
            try {
                if (value != null && !value.isClosed())
                    value.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        values.clear();
    }

    private void applyBindings(Map<String, Object> bindingsMap) throws JavetException {
        if (bindingsMap == null || bindingsMap.isEmpty()) {
            return;
        }

        V8ValueObject globalObject = trackV8Value(v8Runtime.getGlobalObject());

        for (Map.Entry<String, Object> entry : bindingsMap.entrySet()) {
            setJavaValueToV8Object(globalObject, entry.getKey(), entry.getValue());
        }
    }

    private void setJavaValueToV8Object(V8ValueObject v8Object, String key, Object value) throws JavetException {
        if (value == null) {
            v8Object.setNull(key);
            return;
        }

        if (value instanceof Number number) {
            setNumberValue(v8Object, key, number);
        } else if (value instanceof Boolean || value instanceof String || value instanceof Character) {
            v8Object.set(key, value);
        } else if (value instanceof Map<?, ?> mapValue) {
            setMapValue(v8Object, key, mapValue);
        } else if (value instanceof List<?> listValue) {
            setListValue(v8Object, key, listValue);
        } else if (value instanceof Collection<?> collectionValue) {
            setCollectionValue(v8Object, key, collectionValue);
        } else if (value.getClass().isArray()) {
            setArrayValue(v8Object, key, value);
        } else if (value instanceof Date dateValue) {
            setDateValue(v8Object, key, dateValue.getTime());
        } else if (value instanceof Calendar calendarValue) {
            setDateValue(v8Object, key, calendarValue.getTimeInMillis());
        } else if (value instanceof Enum<?>) {
            v8Object.set(key, value.toString());
        } else if (value instanceof Pattern patternValue) {
            setPatternValue(v8Object, key, patternValue);
        } else if (value instanceof URL || value instanceof URI || value instanceof Class<?>) {
            v8Object.set(key, value.toString());
        } else if (value instanceof Optional<?> optionalValue) {
            if (optionalValue.isPresent()) {
                setJavaValueToV8Object(v8Object, key, optionalValue.get());
            } else {
                v8Object.setNull(key);
            }
        } else {
            v8Object.set(key, value.toString());
        }
    }

    private void setNumberValue(V8ValueObject v8Object, String key, Number value) throws JavetException {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            v8Object.set(key, value.intValue());
        } else if (value instanceof Long) {
            long longVal = value.longValue();
            if (longVal <= Integer.MAX_VALUE && longVal >= Integer.MIN_VALUE) {
                v8Object.set(key, (int) longVal);
            } else {
                v8Object.set(key, longVal);
            }
        } else if (value instanceof Float || value instanceof Double) {
            v8Object.set(key, value.doubleValue());
        } else {
            v8Object.set(key, value.doubleValue());
        }
    }


    private void setMapValue(V8ValueObject v8Object, String key, Map<?, ?> mapValue) throws JavetException {
        V8ValueObject mapObj = trackV8Value(v8Runtime.createV8ValueObject());
        setMapToV8Object(mapObj, mapValue);
        v8Object.set(key, mapObj);
    }

    private void setMapToV8Object(V8ValueObject v8Object, Map<?, ?> map) throws JavetException {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String mapKey = String.valueOf(entry.getKey());
            setJavaValueToV8Object(v8Object, mapKey, entry.getValue());
        }
    }

    private void setListValue(V8ValueObject v8Object, String key, List<?> listValue) throws JavetException {
        V8ValueArray arrayObj = trackV8Value(v8Runtime.createV8ValueArray());
        setListToV8Array(arrayObj, listValue);
        v8Object.set(key, arrayObj);
    }

    private void setListToV8Array(V8ValueArray array, List<?> list) throws JavetException {
        for (int i = 0; i < list.size(); i++) {
            setArrayItem(array, i, list.get(i));
        }
    }

    private void setCollectionValue(V8ValueObject v8Object, String key, Collection<?> collectionValue) throws JavetException {
        V8ValueArray arrayObj = trackV8Value(v8Runtime.createV8ValueArray());
        setCollectionToV8Array(arrayObj, collectionValue);
        v8Object.set(key, arrayObj);
    }

    private void setCollectionToV8Array(V8ValueArray array, Collection<?> collection) throws JavetException {
        int i = 0;
        for (Object item : collection) {
            setArrayItem(array, i++, item);
        }
    }

    private void setArrayValue(V8ValueObject v8Object, String key, Object arrayObj) throws JavetException {
        V8ValueArray arrayValue = trackV8Value(v8Runtime.createV8ValueArray());
        setArrayToV8Array(arrayValue, arrayObj);
        v8Object.set(key, arrayValue);
    }

    private void setArrayToV8Array(V8ValueArray array, Object arrayObj) throws JavetException {
        int length = Array.getLength(arrayObj);
        for (int i = 0; i < length; i++) {
            setArrayItem(array, i, Array.get(arrayObj, i));
        }
    }

    private void setDateValue(V8ValueObject v8Object, String key, long timeInMillis) throws JavetException {
        V8ValueObject dateObj = trackV8Value(v8Runtime.getExecutor("new Date(" + timeInMillis + ")").executeObject());
        trackV8Value(dateObj);
        v8Object.set(key, dateObj);
    }

    private void setPatternValue(V8ValueObject v8Object, String key, Pattern pattern) throws JavetException {
        V8ValueObject regexObj = trackV8Value(v8Runtime.getExecutor("new RegExp('" +
                pattern.pattern().replace("'", "\\'") + "')").executeObject());
        trackV8Value(regexObj);
        v8Object.set(key, regexObj);
    }

    private void setArrayItem(V8ValueArray array, int index, Object item) throws JavetException {
        if (item == null) {
            array.setNull(index);
            return;
        }

        if (item instanceof Number number) {
            if (number instanceof Integer || number instanceof Long) {
                array.set(index, number.longValue());
            } else {
                array.set(index, number.doubleValue());
            }
        } else if (item instanceof Boolean || item instanceof String || item instanceof Character) {
            array.set(index, item);
        } else if (item instanceof Date date) {
            V8ValueObject dateObj = trackV8Value(v8Runtime.getExecutor("new Date(" + date.getTime() + ")").executeObject());
            array.set(index, dateObj);
        } else {
            array.set(index, item.toString());
        }
    }

    private Object convertToJavaObject(Object result) throws JavetException {
        if (!(result instanceof V8Value v8Value)) {
            return result;
        }

        try (v8Value) {
            if (v8Value.isNull() || v8Value.isUndefined()) {
                return null;
            } else if (v8Value instanceof V8ValueBoolean v8Boolean) {
                return v8Boolean.getValue();
            } else if (v8Value instanceof V8ValueDouble v8Double) {
                return v8Double.getValue();
            } else if (v8Value instanceof V8ValueInteger v8Integer) {
                return v8Integer.getValue();
            } else if (v8Value instanceof V8ValueLong v8Long) {
                return v8Long.getValue();
            } else if (v8Value instanceof V8ValueString v8String) {
                return v8String.getValue();
            }
            return v8Value.toString();
        }
    }

    @Override
    public void close() throws IOException {
        closeV8Values(managedV8Values);
    }
}