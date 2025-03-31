package com.extendedclip.papi.expansion.javascript.evaluator;

import com.caoccao.javet.enums.JSRuntimeType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.engine.IJavetEngine;
import com.caoccao.javet.interop.engine.JavetEngineConfig;
import com.caoccao.javet.interop.engine.JavetEnginePool;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.extendedclip.papi.expansion.javascript.evaluator.DependLoader.LOGGER;

public final class JavetScriptEvaluatorFactory implements ScriptEvaluatorFactory, Closeable {
    private final JavetEnginePool<V8Runtime> enginePool;
    private final ConcurrentHashMap<JavetScriptEvaluator, Boolean> activeEvaluators;
    private final ScheduledExecutorService cleanupService;

    private final boolean enableResourceTracking;

    private volatile boolean closed = false;

    public JavetScriptEvaluatorFactory(int poolSize, int poolCleanupIntervalSeconds, boolean enableResourceTracking, boolean gc) {
        var poolSize1 = Math.max(1, poolSize);

        this.enableResourceTracking = enableResourceTracking;

        JavetEngineConfig config = new JavetEngineConfig();
        config.setAllowEval(true);
        config.setGlobalName("globalThis");
        config.setJSRuntimeType(JSRuntimeType.V8);
        config.setPoolMaxSize(poolSize1);
        config.setGCBeforeEngineClose(gc);

        this.enginePool = new JavetEnginePool<>(config);

        this.activeEvaluators = new ConcurrentHashMap<>();

        this.cleanupService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "javet-cleanup-thread");
            thread.setDaemon(true);
            return thread;
        });

        if (poolCleanupIntervalSeconds > 0) {
            this.cleanupService.scheduleAtFixedRate(
                    this::performCleanup,
                    poolCleanupIntervalSeconds,
                    poolCleanupIntervalSeconds,
                    TimeUnit.SECONDS
            );
        }
    }

    public static ScriptEvaluatorFactory create(boolean gc, int poolSize) {
        return new JavetScriptEvaluatorFactory(poolSize, 60, true, gc);
    }

    public ScriptEvaluator create() throws EvaluatorException {
        return create(null);
    }

    @SuppressWarnings("unchecked")
    public ScriptEvaluator create(Map<String, Object> bindings) throws EvaluatorException {
        if (closed) {
            throw new EvaluatorException("Factory has been closed");
        }

        try {
            IJavetEngine<V8Runtime> engine = enginePool.getEngine();
            V8Runtime runtime = engine.getV8Runtime();

            JavetScriptEvaluator evaluator = new JavetScriptEvaluator(runtime, bindings);

            if (enableResourceTracking) {
                activeEvaluators.put(evaluator, Boolean.TRUE);

                evaluator.setRuntimeReleaseHook((ev) -> {
                    try {
                        activeEvaluators.remove(ev);

                        enginePool.releaseEngine(engine);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to release V8 runtime", e);
                    }
                });
            }

            return evaluator;
        } catch (Exception e) {
            throw new EvaluatorException("Create JavaScript evaluator failed: " + e.getMessage(), e);
        }
    }

    private void performCleanup() {
        try {
            JavetScriptEvaluator.performGlobalCleanup();

            for (JavetScriptEvaluator evaluator : activeEvaluators.keySet()) {
                if (evaluator.isClosed())
                    activeEvaluators.remove(evaluator);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to perform resource cleanup", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;

        try {
            cleanupService.shutdown();
            cleanupService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to shutdown Javet cleanup service", e);
        }

        if (!activeEvaluators.isEmpty()) {
            LOGGER.info("Closing {} active evaluators...", activeEvaluators.size());

            for (JavetScriptEvaluator evaluator : activeEvaluators.keySet()) {
                try {
                    evaluator.close();
                } catch (Exception e) {
                    LOGGER.error("Closing evaluator failed: " + e.getMessage() + " ... ");
                }
            }

            activeEvaluators.clear();
        }

        try {
            enginePool.close();
            LOGGER.info("JavetScriptEvaluatorFactory is closed");
        } catch (Exception e) {
            throw new IOException("Closing JavetScriptEvaluatorFactory failed:", e);
        }
    }

    public int getActiveEvaluatorsCount() {
        return activeEvaluators.size();
    }

    public int getActiveEngineCount() {
        return enginePool.getActiveEngineCount();
    }

    public int getIdleEngineCount() {
        return enginePool.getIdleEngineCount();
    }

    public void resetPool() {
        try {
            for (JavetScriptEvaluator evaluator : activeEvaluators.keySet()) {
                try {
                    evaluator.close();
                } catch (Exception ignore) {
                }
            }
            activeEvaluators.clear();

            enginePool.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset Javet engine pool", e);
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