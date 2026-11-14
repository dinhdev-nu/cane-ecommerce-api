package vn.cane.support.web;

public enum ApiErrorCode {
    INVALID_INPUT("Dữ liệu gửi lên không hợp lệ."),
    AUTH_REQUIRED("Không thể xác thực. Vui lòng đăng nhập lại."),
    FORBIDDEN("Bạn không có quyền thực hiện thao tác này."),
    NOT_FOUND("Không tìm thấy tài nguyên."),
    REQUEST_IN_PROGRESS("Yêu cầu đang được xử lý. Vui lòng thử lại."),
    IDEMPOTENCY_MISMATCH("Mã thao tác đã được dùng cho yêu cầu khác."),
    RATE_LIMITED("Bạn gửi yêu cầu quá nhanh. Vui lòng chờ rồi thử lại."),
    SERVICE_UNAVAILABLE("Hệ thống tạm thời không sẵn sàng. Vui lòng thử lại.");

    private final String message;

    ApiErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
