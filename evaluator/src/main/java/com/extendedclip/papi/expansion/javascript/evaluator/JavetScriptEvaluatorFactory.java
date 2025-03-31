package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.enums.JSRuntimeType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.engine.IJavetEngine;
import com.caoccao.javet.interop.engine.JavetEngineConfig;
import com.caoccao.javet.interop.engine.JavetEnginePool;

import java.util.Map;

public final class JavetScriptEvaluatorFactory implements ScriptEvaluatorFactory {
    private final JavetEnginePool<V8Runtime> enginePool;

    private JavetScriptEvaluatorFactory(JavetEngineConfig config) {
        this.enginePool = new JavetEnginePool<>(config);
    }

    public static ScriptEvaluatorFactory create(boolean gc, int poolSize) {
        JavetEngineConfig config = new JavetEngineConfig();
        config.setJSRuntimeType(JSRuntimeType.V8);
        config.setAllowEval(true);
        config.setGCBeforeEngineClose(gc);
        config.setPoolMaxSize(poolSize);

        return new JavetScriptEvaluatorFactory(config);
    }

    @Override
    public ScriptEvaluator create(final Map<String, Object> bindings) {
        try {
            IJavetEngine<V8Runtime> engine = enginePool.getEngine();
            V8Runtime runtime = engine.getV8Runtime();

            return new JavetScriptEvaluator(runtime, bindings) {
                @Override
                public void close() {
                    try {
                        super.close();
                        if (engine != null && !engine.isClosed()) {
                            engine.close();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to close Javet evaluator", e);
                    }
                }
            };
        } catch (JavetException e) {
            throw new RuntimeException("Failed to create Javet evaluator", e);
        }
    }

    @Override
    public void cleanBinaries() {
        dispose();
    }

    private void dispose() {
        try {
            if (enginePool != null) {
                enginePool.close();
            }
        } catch (JavetException e) {
            throw new RuntimeException("Failed to dispose Javet engine pool", e);
        }
    }
}