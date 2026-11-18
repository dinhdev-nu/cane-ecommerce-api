package vn.cane.worker;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.cane.admin.application.SessionCleanupService;

@Component
@Profile("worker & !api")
public final class OperationalJobs {
    private final ReminderService reminders;
    private final SessionCleanupService sessions;

    public OperationalJobs(ReminderService reminders, SessionCleanupService sessions) {
        this.reminders = reminders;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void manualDue() {
        reminders.manualDue();
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void stale() {
        reminders.stale();
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void sessions() {
        sessions.deleteExpired();
    }
}
