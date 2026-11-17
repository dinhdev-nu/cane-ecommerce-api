package vn.cane.admin.application;

import java.time.Instant;

public interface ExpiredSessions {
    int deleteThrough(Instant now);
}
