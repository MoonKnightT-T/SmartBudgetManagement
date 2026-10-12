package util;

/** Thrown when a request must be answered with an HTTP error (401, 404, 409 ...). */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
