package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.engine.JavetEngineConfig;
import com.caoccao.javet.interop.engine.JavetEnginePool;

import java.util.Map;

public final class JavetScriptEvaluatorFactory implements ScriptEvaluatorFactory {
    private final JavetEnginePool<V8Runtime> enginePool;
    private final ThreadLocal<V8Runtime> runtimeHolder;

    private JavetScriptEvaluatorFactory() {
        JavetEngineConfig config = new JavetEngineConfig();
        config.setAllowEval(true);
        config.setGCBeforeEngineClose(false);

        this.enginePool = new JavetEnginePool<>(config);
        this.runtimeHolder = new ThreadLocal<>();
    }

    public static ScriptEvaluatorFactory create() {
        return new JavetScriptEvaluatorFactory();
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
                if (!runtime.isClosed()) {
                    runtime.close();
                }
                runtimeHolder.remove();
            }
            enginePool.close();
        } catch (JavetException e) {
            throw new RuntimeException("Failed to dispose Javet engine pool", e);
        }
    }
}