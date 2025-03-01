package com.extendedclip.papi.expansion.javascript.evaluator;

public final class EvaluatorException extends RuntimeException {
    public EvaluatorException(String message, Throwable cause) {
        super(message, cause);
    }

    public EvaluatorException(String message) {
        super(message);
    }
}
