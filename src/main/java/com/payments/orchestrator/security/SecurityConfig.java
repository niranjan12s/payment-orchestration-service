package com.payments.orchestrator.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<SecurityFilter> securityFilterRegistration(SecurityFilter filter) {
        FilterRegistrationBean<SecurityFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        // Intercept all endpoints under payments orchestration
        registration.addUrlPatterns("/api/v1/payments-orchestration/*");
        // Ensure security validation runs at the very beginning of the filter chain
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
