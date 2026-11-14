package vn.cane.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffSessionFilter;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

/** Session foundation; me/logout/login are introduced by their subsequent WP01 tasks. */
@Configuration(proxyBeanMethods = false)
@Profile("api & !worker")
public class SecurityConfiguration {
    @Bean
    WebSecurityCustomizer rejectedRequestErrors(ApiErrors errors) {
        return web -> web.requestRejectedHandler(
                (request, response, failure) -> errors.write(request, response, 400, ApiErrorCode.INVALID_INPUT));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ApiErrors apiErrors, StaffSessionService sessions, SessionCookies cookies)
            throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("__Host-cane-csrf");
        csrfRepository.setHeaderName("X-CSRF-Token");
        csrfRepository.setCookieCustomizer(
                cookie -> cookie.secure(true).sameSite("Lax").path("/"));
        return http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .addFilterAfter(new StaffSessionFilter(sessions, cookies, apiErrors), CsrfFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .sessionManagement(policy -> policy.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                                apiErrors.write(request, response, 403, ApiErrorCode.FORBIDDEN))
                        .accessDeniedHandler((request, response, exception) ->
                                apiErrors.write(request, response, 403, ApiErrorCode.FORBIDDEN)))
                .build();
    }
}
