package org.babyfish.jimmer.client;

public class IllegalDocMetaException extends RuntimeException {

    public IllegalDocMetaException(String message) {
        super(message);
    }

    public IllegalDocMetaException(String message, Throwable cause) {
        super(message, cause);
    }
}
