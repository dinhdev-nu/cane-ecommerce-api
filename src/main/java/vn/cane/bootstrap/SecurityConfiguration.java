package vn.cane.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.admin.infrastructure.security.AdminOriginFilter;
import vn.cane.admin.infrastructure.security.AdminRequestOrigin;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffCsrf;
import vn.cane.admin.infrastructure.security.StaffSessionFilter;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

/** Opens only me/logout; login is introduced by WP01-11. */
@Configuration(proxyBeanMethods = false)
@Profile("api & !worker")
public class SecurityConfiguration {
    @Bean
    AdminRequestOrigin adminRequestOrigin(
            @Value("${cane.http.public-origin}") String origin, @Value("${cane.environment}") String environment) {
        return new AdminRequestOrigin(origin, environment);
    }

    @Bean
    WebSecurityCustomizer rejectedRequestErrors(ApiErrors errors) {
        return web -> web.requestRejectedHandler(
                (request, response, failure) -> errors.write(request, response, 400, ApiErrorCode.INVALID_INPUT));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiErrors apiErrors,
            StaffSessionService sessions,
            SessionCookies cookies,
            StaffCsrf staffCsrf,
            AdminRequestOrigin origin)
            throws Exception {
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(
                                org.springframework.http.HttpMethod.GET, "/api/v1/admin/me")
                        .permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/admin/auth/logout")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .addFilterBefore(new AdminOriginFilter(origin, apiErrors), CsrfFilter.class)
                .addFilterAfter(new StaffSessionFilter(sessions, cookies, apiErrors), CsrfFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(staffCsrf)
                        .csrfTokenRequestHandler(staffCsrf)
                        // Cookie lookup is not a new login. Custom login runs StaffCsrf.afterLogin after commit.
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .requireCsrfProtectionMatcher(AdminRequestOrigin::requiresProtection))
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
