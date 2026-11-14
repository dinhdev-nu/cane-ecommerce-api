package vn.cane.support.ratelimit;

public enum RateGroup {
    LOGIN("login", 5),
    QUOTE("quote", 30),
    PLACE_ORDER("place", 10),
    GUEST_READ("guest-read", 60),
    CANCEL_REQUEST("cancel-request", 5);

    private final String key;
    private final int limit;

    RateGroup(String key, int limit) {
        this.key = key;
        this.limit = limit;
    }

    public String key() {
        return key;
    }

    public int limit() {
        return limit;
    }
}
