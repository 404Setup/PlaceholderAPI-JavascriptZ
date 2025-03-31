package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.enums.JSRuntimeType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.engine.JavetEngineConfig;
import com.caoccao.javet.interop.engine.JavetEnginePool;

import java.util.Map;

public final class JavetScriptNodeEvaluatorFactory implements ScriptEvaluatorFactory {
    private final JavetEnginePool<NodeRuntime> enginePool;
    private final ThreadLocal<NodeRuntime> runtimeHolder;

    private JavetScriptNodeEvaluatorFactory(boolean gc, int poolSize) {
        JavetEngineConfig config = new JavetEngineConfig();
        config.setJSRuntimeType(JSRuntimeType.Node);
        config.setAllowEval(true);
        config.setGCBeforeEngineClose(gc);
        config.setPoolMaxSize(poolSize);

        this.enginePool = new JavetEnginePool<>(config);
        this.runtimeHolder = new ThreadLocal<>();

    }

    public static ScriptEvaluatorFactory create(boolean gc, int poolSize) {
        return new JavetScriptNodeEvaluatorFactory(gc, poolSize);
    }

    @Override
    public ScriptEvaluator create(final Map<String, Object> bindings) {
        try {
            NodeRuntime runtime = getOrCreateRuntime();
            return new JavetScriptEvaluator(runtime, bindings);
        } catch (JavetException e) {
            throw new RuntimeException("Failed to create Javet evaluator", e);
        }
    }

    private NodeRuntime getOrCreateRuntime() throws JavetException {
        NodeRuntime runtime = runtimeHolder.get();
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
            NodeRuntime runtime = runtimeHolder.get();
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