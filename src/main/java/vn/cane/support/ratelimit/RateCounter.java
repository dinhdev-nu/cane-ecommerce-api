package vn.cane.support.ratelimit;

public interface RateCounter {
    long WINDOW_MILLIS = 60_000;

    Hit increment(String key);

    record Hit(long count, long remainingMillis) {
        public Hit {
            if (count < 1 || remainingMillis < 0 || remainingMillis > WINDOW_MILLIS) {
                throw new IllegalStateException("Invalid rate counter result");
            }
        }
    }
}
