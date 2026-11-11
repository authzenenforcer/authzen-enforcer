package io.github.authzenenforcer.spring.servlet;

import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.spring.AuthZenAutoConfiguration;
import io.github.authzenenforcer.spring.AuthZenProperties;
import io.github.authzenenforcer.spring.internal.CurrentAuthentication;
import io.github.authzenenforcer.spring.internal.HandlerPermissions;
import io.github.authzenenforcer.spring.internal.PermissionInterceptor;
import io.github.authzenenforcer.spring.internal.ProtectedRoutesCheck;
import io.github.authzenenforcer.spring.internal.SecurityClaimsExtractor;
import io.github.authzenenforcer.spring.internal.SecurityResultHandler;
import io.github.authzenenforcer.spring.internal.StatusResultHandler;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.Optional;

/**
 * Auto-configuration of enforcement in Spring MVC: an interceptor asks the {@link Enforcer} before
 * every enforced handler method runs. Those are the handler methods annotated with
 * {@link io.github.authzenenforcer.spring.CheckPermission}, or with
 * {@code authzen.enforcer.enforce-all}, all of them.
 *
 * <p>Once the application context is ready, every enforced route is checked: invalid annotations
 * fail the startup, and routes whose requests would be refused are logged as warnings.
 */
@AutoConfiguration(after = AuthZenAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HandlerInterceptor.class, HttpServletRequest.class})
public class AuthZenWebMvcAutoConfiguration {

    /**
     * Creates the auto-configuration.
     */
    public AuthZenWebMvcAutoConfiguration() {
        // Instantiated by Spring
    }

    /**
     * Creates the extractor of the caller's claims for applications without Spring Security: every
     * caller is anonymous.
     *
     * @return the extractor
     */
    @Bean
    @ConditionalOnMissingBean
    public ClaimsExtractor authZenClaimsExtractor() {
        return request -> Optional.empty();
    }

    /**
     * Creates the handler of requests that are not let through for applications without Spring
     * Security's web filters: it sends the status.
     *
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean
    public EnforcementResultHandler authZenEnforcementResultHandler() {
        return new StatusResultHandler();
    }

    /**
     * Creates the record of how handler methods are enforced.
     *
     * @param properties the properties
     * @return the permissions
     */
    @Bean
    public HandlerPermissions authZenHandlerPermissions(AuthZenProperties properties) {
        return new HandlerPermissions(properties.getEnforcer().isEnforceAll(),
                properties.getEnforcer().getExcludePaths());
    }

    /**
     * Creates the check of the enforced routes at startup.
     *
     * @param beans       the bean factory
     * @param enforcer    the enforcer
     * @param permissions how handlers are enforced
     * @return the check
     */
    @Bean
    public ProtectedRoutesCheck authZenProtectedRoutesCheck(ListableBeanFactory beans, Enforcer enforcer,
            HandlerPermissions permissions) {
        return new ProtectedRoutesCheck(beans, enforcer, permissions);
    }

    /**
     * Registers the interceptor with every handler mapping of the context, those of Spring Boot
     * Actuator included, ahead of the interceptors registered through {@code WebMvcConfigurer}, so
     * that nothing of a handler runs before it.
     *
     * @param enforcer        the enforcer
     * @param permissions     how handlers are enforced
     * @param claimsExtractor the claims extractor
     * @param resultHandler   the result handler
     * @param authentication  the authentication of expressions, with Spring Security
     * @return the interceptor
     */
    @Bean
    public MappedInterceptor authZenPermissionInterceptor(Enforcer enforcer, HandlerPermissions permissions,
            ClaimsExtractor claimsExtractor, EnforcementResultHandler resultHandler,
            ObjectProvider<CurrentAuthentication> authentication) {
        return new MappedInterceptor(null, new PermissionInterceptor(enforcer, permissions, claimsExtractor,
                resultHandler, authentication.getIfAvailable()));
    }

    // Member classes are processed before the bean methods above, so their beans come first

    /** Claims and the authentication from Spring Security. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ClaimsExtractor authZenSecurityClaimsExtractor() {
            return new SecurityClaimsExtractor();
        }

        @Bean
        CurrentAuthentication authZenCurrentAuthentication() {
            return new CurrentAuthentication();
        }
    }

    /** Responses through Spring Security's exception handling, which its web filters do. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.web.access.ExceptionTranslationFilter")
    static class WebSecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        EnforcementResultHandler authZenSecurityResultHandler() {
            return new SecurityResultHandler();
        }
    }
}
