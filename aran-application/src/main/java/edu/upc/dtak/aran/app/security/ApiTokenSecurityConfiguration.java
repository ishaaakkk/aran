package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for {@link ApiTokenAuthenticationFilter}.
 *
 * <p>The filter is a bean so {@code SecurityConfig} can inject it, which means Spring Boot would
 * otherwise also register it directly with the servlet container — running it a second time,
 * outside the security chain, on every request including static resources. The
 * {@link FilterRegistrationBean} below disables that automatic registration, exactly as
 * {@code RateLimitingFilter}'s javadoc describes for the same hazard.
 */
@Configuration
public class ApiTokenSecurityConfiguration {

    @Bean
    ApiTokenAuthenticationFilter apiTokenAuthenticationFilter(
            ApiTokenService tokens, UserRepository users, TenantRepository tenants) {
        return new ApiTokenAuthenticationFilter(tokens, users, tenants);
    }

    @Bean
    FilterRegistrationBean<ApiTokenAuthenticationFilter> apiTokenFilterRegistration(
            ApiTokenAuthenticationFilter filter) {
        FilterRegistrationBean<ApiTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
