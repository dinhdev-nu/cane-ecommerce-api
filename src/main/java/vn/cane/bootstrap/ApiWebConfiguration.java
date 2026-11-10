package vn.cane.bootstrap;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import vn.cane.support.web.ApiErrors;
import vn.cane.support.web.JsonBodyFilter;
import vn.cane.support.web.RequestTraceFilter;

@Configuration(proxyBeanMethods = false)
@Profile("api & !worker")
public class ApiWebConfiguration {
    @Bean
    FilterRegistrationBean<RequestTraceFilter> requestTraceFilter(ApiErrors errors) {
        var registration = new FilterRegistrationBean<>(new RequestTraceFilter(errors));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<JsonBodyFilter> jsonBodyFilter(ApiErrors errors, SecurityFilterProperties security) {
        var registration = new FilterRegistrationBean<>(new JsonBodyFilter(errors));
        registration.setOrder(security.getOrder() + 1);
        return registration;
    }
}
