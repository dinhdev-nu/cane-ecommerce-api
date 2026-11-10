package vn.cane.support.web;

public enum ApiErrorCode {
    INVALID_INPUT("Dữ liệu gửi lên không hợp lệ."),
    AUTH_REQUIRED("Không thể xác thực. Vui lòng đăng nhập lại."),
    FORBIDDEN("Bạn không có quyền thực hiện thao tác này."),
    NOT_FOUND("Không tìm thấy tài nguyên."),
    SERVICE_UNAVAILABLE("Hệ thống tạm thời không sẵn sàng. Vui lòng thử lại.");

    private final String message;

    ApiErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
