package io.dbtools.openwebui.api;

/**
 * Ошибка обращения к Open WebUI: сетевая, HTTP или формат ответа.
 */
public class OpenWebUIException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public OpenWebUIException(String message) {
        this(message, -1, null);
    }

    public OpenWebUIException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public OpenWebUIException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP-код ответа или -1, если до ответа дело не дошло. */
    public int getStatusCode() {
        return statusCode;
    }
}
