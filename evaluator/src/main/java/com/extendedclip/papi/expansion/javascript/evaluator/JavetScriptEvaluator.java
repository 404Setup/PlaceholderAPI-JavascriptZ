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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.extendedclip.papi.expansion.javascript.evaluator.DependLoader.LOGGER;

public class JavetScriptEvaluator implements ScriptEvaluator, Closeable {
    private static final ThreadLocal<Set<V8Value>> THREAD_V8_VALUES = ThreadLocal.withInitial(HashSet::new);
    private static final ConcurrentHashMap<V8Runtime, Set<V8Value>> RUNTIME_VALUES = new ConcurrentHashMap<>();
    private static final int BATCH_CLOSE_THRESHOLD = 100;
    private static final int PERSISTENCE_THRESHOLD = 5;
    private final V8Runtime v8Runtime;
    private final Map<String, Object> bindings;
    private final Set<V8Value> managedV8Values;
    private final Map<V8Value, Integer> valueUsageCounter = new HashMap<>();
    private Consumer<JavetScriptEvaluator> runtimeReleaseHook;
    private boolean closed = false;

    public JavetScriptEvaluator(final V8Runtime v8Runtime, final Map<String, Object> bindings) {
        this.v8Runtime = v8Runtime;
        this.bindings = bindings != null ? new HashMap<>(bindings) : new HashMap<>();
        this.managedV8Values = RUNTIME_VALUES.computeIfAbsent(v8Runtime, k -> Collections.newSetFromMap(new WeakHashMap<>()));
    }

    public static void performGlobalCleanup() {
        THREAD_V8_VALUES.remove();

        RUNTIME_VALUES.entrySet().removeIf(entry -> {
            V8Runtime runtime = entry.getKey();
            return runtime == null || runtime.isClosed();
        });
    }

    protected <T extends V8Value> T trackV8Value(T value) {
        if (value != null) {
            THREAD_V8_VALUES.get().add(value);
            managedV8Values.add(value);

            valueUsageCounter.merge(value, 1, Integer::sum);
        }
        return value;
    }

    public void setRuntimeReleaseHook(Consumer<JavetScriptEvaluator> hook) {
        this.runtimeReleaseHook = hook;
    }

    @Override
    public Object execute(final Map<String, Object> additionalBindings, final String script)
            throws EvaluatorException {
        if (closed) {
            throw new EvaluatorException("Evaluator has been closed");
        }

        if (v8Runtime == null || v8Runtime.isClosed()) {
            throw new EvaluatorException("JavaScript runtime is not available");
        }

        try {
            applyBindings(bindings);

            if (additionalBindings != null && !additionalBindings.isEmpty()) {
                applyBindings(additionalBindings);
            }

            Object result = v8Runtime.getExecutor(script).execute();

            if (result != null) {
                result = convertToJavaObject(result);
                cleanupTemporaryValues();
            }

            return result;
        } catch (JavetException e) {
            throw new EvaluatorException("Script execution failed: " + e.getMessage(), e);
        }
    }

    private void cleanupTemporaryValues() {
        Set<V8Value> threadValues = THREAD_V8_VALUES.get();

        if (threadValues.size() < BATCH_CLOSE_THRESHOLD) {
            return;
        }

        List<V8Value> valuesToClose = new ArrayList<>(threadValues.size());
        for (V8Value value : threadValues) {
            if (value != null && !value.isClosed() &&
                    (valueUsageCounter.getOrDefault(value, 0) < PERSISTENCE_THRESHOLD)) {
                valuesToClose.add(value);
            }
        }

        int closedCount = closeV8ValuesEfficiently(valuesToClose);

        if (closedCount > 0) {
            valuesToClose.forEach(threadValues::remove);
        }
    }

    private int closeV8ValuesEfficiently(List<V8Value> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }

        int closedCount = 0;
        Map<Class<?>, List<V8Value>> valuesByType = new HashMap<>();

        for (V8Value value : values) {
            if (value == null || value.isClosed()) continue;

            valuesByType.computeIfAbsent(value.getClass(), k -> new ArrayList<>()).add(value);
        }

        for (List<V8Value> typeValues : valuesByType.values()) {
            try {
                for (V8Value value : typeValues) {
                    value.close();
                    closedCount++;

                    valueUsageCounter.remove(value);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to close V8Value batch", e);
            }
        }

        return closedCount;
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
        if (closed) {
            return;
        }

        Set<V8Value> threadValues = THREAD_V8_VALUES.get();

        List<V8Value> valuesToClose = new ArrayList<>();

        for (V8Value value : threadValues) {
            if (value != null && !value.isClosed()) {
                valuesToClose.add(value);
            }
        }

        closeV8ValuesEfficiently(valuesToClose);
        threadValues.clear();
        valueUsageCounter.clear();

        if (v8Runtime != null) {
            RUNTIME_VALUES.remove(v8Runtime);
        }

        closed = true;

        if (runtimeReleaseHook != null) {
            try {
                runtimeReleaseHook.accept(this);
            } catch (Exception e) {
                LOGGER.warn("Release hook failed to execute. " + e);
            }
        }

    }

    public boolean isClosed() {
        return closed;
    }
}