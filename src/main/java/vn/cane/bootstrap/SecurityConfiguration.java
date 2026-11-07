package vn.cane.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/** PREP-02 exposes no business endpoints. Staff authentication and CSRF belong to WP01. */
@Configuration(proxyBeanMethods = false)
@Profile("api & !worker")
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("__Host-cane-csrf");
        csrfRepository.setHeaderName("X-CSRF-Token");
        csrfRepository.setCookieCustomizer(
                cookie -> cookie.secure(true).sameSite("Lax").path("/"));
        return http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(403);
                    response.setHeader("Cache-Control", "no-store");
                }))
                .build();
    }
}
