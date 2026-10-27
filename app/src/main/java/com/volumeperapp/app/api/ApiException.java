package com.volumeperapp.app.api;

/** A failure the caller should see as a status and a sentence, not a 500. */
public final class ApiException extends Exception {

    public final int status;
    public final String hint;

    public ApiException(int status, String message, String hint) {
        super(message);
        this.status = status;
        this.hint = hint;
    }
}
