package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.enums.JSRuntimeType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.engine.JavetEngineConfig;
import com.caoccao.javet.interop.engine.JavetEnginePool;

import java.util.Map;

public final class JavetScriptEvaluatorFactory implements ScriptEvaluatorFactory {
    private final JavetEnginePool<V8Runtime> enginePool;
    private final ThreadLocal<V8Runtime> runtimeHolder;

    private JavetScriptEvaluatorFactory(boolean gc) {
        JavetEngineConfig config = new JavetEngineConfig();
        config.setJSRuntimeType(JSRuntimeType.V8);
        config.setAllowEval(true);
        config.setGCBeforeEngineClose(gc);

        this.enginePool = new JavetEnginePool<>(config);
        this.runtimeHolder = new ThreadLocal<>();
    }

    public static ScriptEvaluatorFactory create(boolean gc) {
        return new JavetScriptEvaluatorFactory(gc);
    }

    @Override
    public ScriptEvaluator create(final Map<String, Object> bindings) {
        try {
            V8Runtime runtime = getOrCreateRuntime();
            return new JavetScriptEvaluator(runtime, bindings);
        } catch (JavetException e) {
            throw new RuntimeException("Failed to create Javet evaluator", e);
        }
    }

    private V8Runtime getOrCreateRuntime() throws JavetException {
        V8Runtime runtime = runtimeHolder.get();
        if (runtime == null || runtime.isClosed()) {
            runtime = enginePool.getEngine().getV8Runtime();
            runtimeHolder.set(runtime);
        }
        return runtime;
    }

    @Override
    public void cleanBinaries() {
        dispose();
    }

    private void dispose() {
        try {
            V8Runtime runtime = runtimeHolder.get();
            if (runtime != null) {
                if (!runtime.isClosed()) runtime.close();
                runtimeHolder.remove();
            }
            enginePool.close();
        } catch (JavetException e) {
            throw new RuntimeException("Failed to dispose Javet engine pool", e);
        }
    }
}