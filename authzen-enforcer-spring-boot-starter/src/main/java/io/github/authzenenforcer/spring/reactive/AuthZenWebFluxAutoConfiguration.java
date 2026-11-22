package io.github.authzenenforcer.spring.reactive;

import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.ResourceInferrer;
import io.github.authzenenforcer.spring.AuthZenAutoConfiguration;
import io.github.authzenenforcer.spring.AuthZenProperties;
import io.github.authzenenforcer.spring.internal.HandlerAdapterOrderCheck;
import io.github.authzenenforcer.spring.internal.HandlerPermissions;
import io.github.authzenenforcer.spring.internal.PermissionHandlerAdapter;
import io.github.authzenenforcer.spring.internal.ProtectedRoutesCheck;
import io.github.authzenenforcer.spring.internal.ReactiveRoutes;
import io.github.authzenenforcer.spring.internal.ReactiveSecurityClaimsExtractor;
import io.github.authzenenforcer.spring.internal.ReactiveSecurityResultHandler;
import io.github.authzenenforcer.spring.internal.ReactiveStatusResultHandler;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Auto-configuration of enforcement in Spring WebFlux: a handler adapter asks the {@link Enforcer}
 * before every enforced handler method runs. Those are the handler methods annotated with
 * {@link io.github.authzenenforcer.spring.CheckPermission}, or with
 * {@code authzen.enforcer.enforce-all}, all of them.
 *
 * <p>Every decision starts on Reactor's bounded elastic scheduler, not on the thread of the
 * request, so that a component of the application that blocks, such as a credential provider that
 * obtains a token, cannot block the event loop. The handler then runs on the thread that completed
 * the decision.
 *
 * <p>Once the application context is ready, every enforced route is checked: invalid annotations
 * fail the startup, and routes whose requests would be refused are logged as warnings.
 */
@AutoConfiguration(after = AuthZenAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({HandlerAdapter.class, Mono.class})
public class AuthZenWebFluxAutoConfiguration {

    private static final String OBSERVATION = "io.micrometer.observation.Observation";

    /**
     * Creates the auto-configuration.
     */
    public AuthZenWebFluxAutoConfiguration() {
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
    public ReactiveClaimsExtractor authZenReactiveClaimsExtractor() {
        return exchange -> Mono.empty();
    }

    /**
     * Creates the handler of requests that are not let through for applications without Spring
     * Security's web filters: it signals the status.
     *
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactiveEnforcementResultHandler authZenReactiveEnforcementResultHandler() {
        return new ReactiveStatusResultHandler();
    }

    /**
     * Creates the record of how handler methods are enforced.
     *
     * @param properties the properties
     * @param context    the application context, for the controller advice
     * @return the permissions
     */
    @Bean
    public HandlerPermissions authZenHandlerPermissions(AuthZenProperties properties, ApplicationContext context) {
        return new HandlerPermissions(properties.getEnforcer().isEnforceAll(),
                properties.getEnforcer().getExcludePaths(), properties.getEnforcer().getResource().getMaxIdLength(),
                HandlerPermissions.controllerAdvice(context));
    }

    /**
     * Creates the check of the enforced routes at startup.
     *
     * @param beans            the bean factory
     * @param enforcer         the enforcer
     * @param permissions      how handlers are enforced
     * @param resourceInferrer the resource inferrer, if there is one, to tell collections apart
     * @return the check
     */
    @Bean
    public ProtectedRoutesCheck authZenProtectedRoutesCheck(ListableBeanFactory beans, Enforcer enforcer,
            HandlerPermissions permissions, ObjectProvider<ResourceInferrer> resourceInferrer) {
        return new ProtectedRoutesCheck(() -> ReactiveRoutes.of(beans), enforcer, permissions,
                resourceInferrer.getIfUnique());
    }

    /**
     * Creates the check, at startup, that no handler adapter of the application runs handler methods
     * before the one that decides about them.
     *
     * @param beans the bean factory
     * @return the check
     */
    @Bean
    public HandlerAdapterOrderCheck authZenHandlerAdapterOrderCheck(ListableBeanFactory beans) {
        return new HandlerAdapterOrderCheck(beans, () -> ReactiveRoutes.of(beans));
    }

    /**
     * Creates the handler adapter that decides before handler methods run. It is ordered first, so
     * that the dispatcher picks it for handler methods of every handler mapping of the context,
     * those of Spring Boot Actuator included.
     *
     * @param enforcer        the enforcer
     * @param permissions     how handlers are enforced
     * @param claimsExtractor the claims extractor
     * @param resultHandler   the result handler
     * @param handlerMethods  the adapter that runs handler methods
     * @return the adapter
     */
    @Bean
    public PermissionHandlerAdapter authZenPermissionHandlerAdapter(Enforcer enforcer,
            HandlerPermissions permissions, ReactiveClaimsExtractor claimsExtractor,
            ReactiveEnforcementResultHandler resultHandler, RequestMappingHandlerAdapter handlerMethods,
            ApplicationContext context) {
        // Visible to the application and to the starter, which links against it
        boolean restoreObservations = ClassUtils.isPresent(OBSERVATION, context.getClassLoader())
                && ClassUtils.isPresent(OBSERVATION, PermissionHandlerAdapter.class.getClassLoader());
        return new PermissionHandlerAdapter(enforcer, permissions, claimsExtractor, resultHandler, handlerMethods,
                Schedulers.boundedElastic(), restoreObservations);
    }

    // Member classes are processed before the bean methods above, so their beans come first

    /** Claims from Spring Security. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.ReactiveSecurityContextHolder")
    static class SecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ReactiveClaimsExtractor authZenReactiveSecurityClaimsExtractor() {
            return new ReactiveSecurityClaimsExtractor();
        }
    }

    /** Responses through Spring Security's exception handling, which its web filters do. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.web.server.authorization.ExceptionTranslationWebFilter")
    static class WebSecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ReactiveEnforcementResultHandler authZenReactiveSecurityResultHandler() {
            return new ReactiveSecurityResultHandler();
        }
    }
}
