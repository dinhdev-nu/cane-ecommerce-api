package vn.cane.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Shared by operational staff commands and staff login. */
@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
    @Bean
    PasswordEncoder staffPasswordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }
}
