package com.carbonflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String message;
    private ErrorDetails error;

    public static class ErrorDetails {
        private String code;
        private String message;

        public ErrorDetails() {}
        public ErrorDetails(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public ApiResponse() {}

    /**
     * Success envelope with the Node backend's default message
     * ({@code sendSuccess(..., 'Success')} — the message field is always
     * present on success responses there, so it is here too).
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, "Success");
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setSuccess(true);
        resp.setData(data);
        resp.setMessage(message);
        return resp;
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setSuccess(false);
        resp.setError(new ErrorDetails(code, message));
        return resp;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public ErrorDetails getError() { return error; }
    public void setError(ErrorDetails error) { this.error = error; }
}
