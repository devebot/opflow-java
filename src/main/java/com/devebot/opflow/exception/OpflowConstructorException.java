package com.devebot.opflow.exception;

/**
 *
 * @author drupalex
 */
public class OpflowConstructorException extends Exception {

    public OpflowConstructorException() {
    }

    public OpflowConstructorException(String message) {
        super(message);
    }

    public OpflowConstructorException(String message, Throwable cause) {
        super(message, cause);
    }

    public OpflowConstructorException(Throwable cause) {
        super(cause);
    }

    public OpflowConstructorException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}