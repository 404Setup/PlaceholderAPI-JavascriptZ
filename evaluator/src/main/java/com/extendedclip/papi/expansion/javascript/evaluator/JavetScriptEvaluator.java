package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.*;
import com.caoccao.javet.values.reference.V8ValueObject;

import java.util.Map;

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
            throw new EvaluatorException("Script execution failed", e);
        }
    }

    private void applyBindings(Map<String, Object> bindingsMap) throws JavetException {
        if (bindingsMap == null || bindingsMap.isEmpty()) {
            return;
        }

        try (V8ValueObject global = v8Runtime.getGlobalObject()) {
            for (Map.Entry<String, Object> entry : bindingsMap.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (value == null) {
                    global.setNull(key);
                } else if (value instanceof Number) {
                    if (value instanceof Integer || value instanceof Long) {
                        global.set(key, ((Number) value).longValue());
                    } else {
                        global.set(key, ((Number) value).doubleValue());
                    }
                } else if (value instanceof Boolean) {
                    global.set(key, value);
                } else if (value instanceof String) {
                    global.set(key, value);
                } else if (value instanceof Map) {
                    try (V8ValueObject obj = v8Runtime.createV8ValueObject()) {
                        setMapValues(obj, (Map<?, ?>) value);
                        global.set(key, obj);
                    }
                }
            }
        }
    }

    private void setMapValues(V8ValueObject obj, Map<?, ?> map) throws JavetException {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            if (value == null) {
                obj.setNull(key);
            } else if (value instanceof Number) {
                if (value instanceof Integer || value instanceof Long) {
                    obj.set(key, ((Number) value).longValue());
                } else {
                    obj.set(key, ((Number) value).doubleValue());
                }
            } else if (value instanceof Boolean) {
                obj.set(key, value);
            } else if (value instanceof String) {
                obj.set(key, value);
            } else if (value instanceof Map) {
                try (V8ValueObject nestedObj = v8Runtime.createV8ValueObject()) {
                    setMapValues(nestedObj, (Map<?, ?>) value);
                    obj.set(key, nestedObj);
                }
            }
        }
    }

    private Object convertToJavaObject(Object result) throws JavetException {
        if (!(result instanceof V8Value)) {
            return result;
        }

        try (V8Value v8Value = (V8Value) result) {
            if (v8Value.isNull() || v8Value.isUndefined()) {
                return null;
            } else if (v8Value instanceof V8ValueBoolean) {
                return ((V8ValueBoolean) v8Value).getValue();
            } else if (v8Value instanceof V8ValueDouble) {
                return ((V8ValueDouble) v8Value).getValue();
            } else if (v8Value instanceof V8ValueInteger) {
                return ((V8ValueInteger) v8Value).getValue();
            } else if (v8Value instanceof V8ValueLong) {
                return ((V8ValueLong) v8Value).getValue();
            } else if (v8Value instanceof V8ValueString) {
                return ((V8ValueString) v8Value).getValue();
            } else if (v8Value instanceof V8ValueObject) {
                return v8Value.toString();
            }
            return v8Value.toString();
        }
    }
}