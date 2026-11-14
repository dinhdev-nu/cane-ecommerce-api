package vn.cane.admin.infrastructure.cli;

import java.util.Arrays;

/** Uses the terminal's no-echo input; redirected standard input is deliberately unsupported. */
public final class ConsolePasswordPrompt implements StaffCommand.PasswordPrompt {
    @Override
    public char[] readConfirmedPassword() {
        var console = System.console();
        if (console == null) throw new IllegalStateException("An interactive console is required");
        char[] password = console.readPassword("New password: ");
        char[] confirmation = null;
        boolean accepted = false;
        try {
            confirmation = console.readPassword("Confirm password: ");
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Password confirmation did not match");
            }
            accepted = true;
            return password;
        } finally {
            if (confirmation != null) Arrays.fill(confirmation, '\0');
            if (!accepted && password != null) Arrays.fill(password, '\0');
        }
    }
}
