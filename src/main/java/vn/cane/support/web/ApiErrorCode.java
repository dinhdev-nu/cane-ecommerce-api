package vn.cane.support.web;

public enum ApiErrorCode {
    SHOP_NOT_ACCEPTING("Shop is not accepting new orders."),
    DELIVERY_NOT_SUPPORTED("Delivery is unavailable for this address."),
    OUTSIDE_NEAR_AREA("This item cannot be delivered to the selected ward."),
    ITEM_UNAVAILABLE("An item is unavailable."),
    INSUFFICIENT_STOCK("An item has insufficient stock."),
    QUOTE_CHANGED("The total has changed. Please review and confirm again."),
    INVALID_INPUT("Dữ liệu gửi lên không hợp lệ."),
    AUTH_REQUIRED("Không thể xác thực. Vui lòng đăng nhập lại."),
    FORBIDDEN("Bạn không có quyền thực hiện thao tác này."),
    NOT_FOUND("Không tìm thấy tài nguyên."),
    ORDER_LINK_INVALID("Link xem đơn không hợp lệ hoặc đã hết hạn."),
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
