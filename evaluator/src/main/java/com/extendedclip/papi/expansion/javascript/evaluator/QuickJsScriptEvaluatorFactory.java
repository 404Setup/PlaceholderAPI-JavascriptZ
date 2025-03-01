package com.extendedclip.papi.expansion.javascript.evaluator;

import javax.script.ScriptException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

public final class QuickJsScriptEvaluatorFactory implements ScriptEvaluatorFactory {
    private static final int TEST_EVALUATION_RESULT = 100;

    private QuickJsScriptEvaluatorFactory() {

    }

    public static ScriptEvaluatorFactory createWithFallback(final Function<Void, ScriptEvaluatorFactory> evaluatorFactoryProducer) {
        try {
            final ScriptEvaluatorFactory evaluatorFactory = create();
            attemptBasicEvaluation(evaluatorFactory);
            return evaluatorFactory;
        } catch (final Exception exception) {
            return evaluatorFactoryProducer.apply(null);
        }
    }

    private static void attemptBasicEvaluation(final ScriptEvaluatorFactory evaluatorFactory) throws ScriptException {
        final Object result = evaluatorFactory.create(Collections.emptyMap()).execute(Collections.emptyMap(), "10 * 10");
        if (result instanceof Integer && (Integer) result == TEST_EVALUATION_RESULT) {
            return;
        }
        throw new RuntimeException("Failed basic evaluation test");
    }

    public static ScriptEvaluatorFactory create() throws URISyntaxException, ReflectiveOperationException, NoSuchAlgorithmException, IOException {
        return new QuickJsScriptEvaluatorFactory();
    }

    @Override
    public ScriptEvaluator create(final Map<String, Object> bindings) {
        return new QuickJsScriptEvaluator(bindings);
    }
}
