package vn.cane.admin.api;

public record AdminLoginRequest(String username, String password) {
    @Override
    public String toString() {
        return "AdminLoginRequest[redacted]";
    }
}
