/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

/**
 * Error of an Open WebUI REST call. {@link #getStatus()} is the HTTP status, 0 for network errors.
 */
public class OwuiException extends Exception {

    private final int status;

    public OwuiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public OwuiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int getStatus() {
        return status;
    }

    /** The chat does not exist (deleted in Open WebUI) or belongs to another user. */
    public boolean isNotFound() {
        return status == 401 || status == 403 || status == 404;
    }

    /** Network problem or server-side error: worth retrying later. */
    public boolean isTransient() {
        return status == 0 || status == 429 || status >= 500;
    }
}
