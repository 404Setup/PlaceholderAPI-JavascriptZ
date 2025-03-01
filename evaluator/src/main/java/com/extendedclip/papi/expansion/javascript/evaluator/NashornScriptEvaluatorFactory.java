package com.extendedclip.papi.expansion.javascript.evaluator;

import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.ScriptEngine;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public final class NashornScriptEvaluatorFactory implements ScriptEvaluatorFactory {
    private final ThreadLocal<ScriptEngine> engines;

    private NashornScriptEvaluatorFactory(final NashornScriptEngineFactory engineFactory) {
        this.engines = ThreadLocal.withInitial(() -> engineFactory.getScriptEngine("--no-java"));
    }

    public static ScriptEvaluatorFactory create() throws URISyntaxException, ReflectiveOperationException, NoSuchAlgorithmException, IOException {
        return new NashornScriptEvaluatorFactory(new NashornScriptEngineFactory());
    }

    @Override
    public ScriptEvaluator create(final Map<String, Object> bindings) {
        return new NashornScriptEvaluator(engines.get(), bindings);
    }

}
