package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.*;
import com.caoccao.javet.values.reference.V8ValueArray;
import com.caoccao.javet.values.reference.V8ValueObject;

import java.lang.reflect.Array;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public final class JavetScriptEvaluator implements ScriptEvaluator {

    private final V8Runtime v8Runtime;
    private final Map<String, Object> bindings;

    public JavetScriptEvaluator(final V8Runtime v8Runtime, final Map<String, Object> bindings) {
        this.v8Runtime = v8Runtime;
        this.bindings = bindings;
    }

    @Override
    public Object execute(final Map<String, Object> additionalBindings, final String script)
            throws EvaluatorException {
        if (v8Runtime.isClosed()) {
            throw new EvaluatorException("V8 runtime is closed");
        }

        try {
            applyBindings(bindings);
            applyBindings(additionalBindings);

            Object result = v8Runtime.getExecutor(script).executeObject();
            return convertToJavaObject(result);
        } catch (JavetException e) {
            if (e.getMessage().contains("SyntaxError")) {
                throw new EvaluatorException("JavaScript syntax error: " + e.getMessage(), e);
            } else if (e.getMessage().contains("ReferenceError")) {
                throw new EvaluatorException("JavaScript reference error: " + e.getMessage(), e);
            } else {
                throw new EvaluatorException("Script execution failed: " + e.getMessage(), e);
            }
        }
    }

    private void applyBindings(Map<String, Object> bindingsMap) throws JavetException {
        if (bindingsMap == null || bindingsMap.isEmpty()) {
            return;
        }

        try (V8ValueObject global = v8Runtime.getGlobalObject()) {
            for (Map.Entry<String, Object> entry : bindingsMap.entrySet()) {
                setJavaValueToV8Object(global, entry.getKey(), entry.getValue());
            }
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
        if (value instanceof Integer || value instanceof Long) {
            v8Object.set(key, value.longValue());
        } else {
            v8Object.set(key, value.doubleValue());
        }
    }

    private void setMapValue(V8ValueObject v8Object, String key, Map<?, ?> mapValue) throws JavetException {
        try (V8ValueObject nestedObj = v8Runtime.createV8ValueObject()) {
            setMapToV8Object(nestedObj, mapValue);
            v8Object.set(key, nestedObj);
        }
    }

    private void setListValue(V8ValueObject v8Object, String key, List<?> listValue) throws JavetException {
        try (V8ValueArray array = v8Runtime.createV8ValueArray()) {
            setListToV8Array(array, listValue);
            v8Object.set(key, array);
        }
    }

    private void setCollectionValue(V8ValueObject v8Object, String key, Collection<?> collectionValue) throws JavetException {
        try (V8ValueArray array = v8Runtime.createV8ValueArray()) {
            setCollectionToV8Array(array, collectionValue);
            v8Object.set(key, array);
        }
    }

    private void setArrayValue(V8ValueObject v8Object, String key, Object arrayObj) throws JavetException {
        try (V8ValueArray array = v8Runtime.createV8ValueArray()) {
            setArrayToV8Array(array, arrayObj);
            v8Object.set(key, array);
        }
    }

    private void setDateValue(V8ValueObject v8Object, String key, long timeInMillis) throws JavetException {
        try (V8ValueObject dateObj = v8Runtime.getExecutor("new Date(" + timeInMillis + ")").executeObject()) {
            v8Object.set(key, dateObj);
        }
    }

    private void setPatternValue(V8ValueObject v8Object, String key, Pattern pattern) throws JavetException {
        try (V8ValueObject regexObj = v8Runtime.getExecutor("new RegExp('" +
                pattern.pattern().replace("'", "\\'") + "')").executeObject()) {
            v8Object.set(key, regexObj);
        }
    }

    private void setMapToV8Object(V8ValueObject v8Object, Map<?, ?> map) throws JavetException {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            setJavaValueToV8Object(v8Object, key, entry.getValue());
        }
    }

    private void setListToV8Array(V8ValueArray array, List<?> list) throws JavetException {
        for (int i = 0; i < list.size(); i++) {
            setArrayItem(array, i, list.get(i));
        }
    }

    private void setCollectionToV8Array(V8ValueArray array, Collection<?> collection) throws JavetException {
        int index = 0;
        for (Object item : collection) {
            setArrayItem(array, index++, item);
        }
    }

    private void setArrayToV8Array(V8ValueArray array, Object arrayObj) throws JavetException {
        int length = Array.getLength(arrayObj);
        for (int i = 0; i < length; i++) {
            setArrayItem(array, i, Array.get(arrayObj, i));
        }
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
            try (V8ValueObject dateObj = v8Runtime.getExecutor("new Date(" + date.getTime() + ")").executeObject()) {
                array.set(index, dateObj);
            }
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
}