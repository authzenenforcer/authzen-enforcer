package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.ActionMapper;
import io.github.authzenenforcer.core.enforce.EnforcementPolicy;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.RequestInferrer;
import io.github.authzenenforcer.core.enforce.ResourceInferrer;
import io.github.authzenenforcer.core.enforce.RouteOverrides;
import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.JsonCodec;
import io.github.authzenenforcer.core.spi.PepListener;
import io.github.authzenenforcer.core.spi.PepTracer;
import io.github.authzenenforcer.spring.internal.EnforcedHandlerTypes;
import io.github.authzenenforcer.spring.internal.AbstractOAuth2ClientCredentials;
import io.github.authzenenforcer.spring.internal.OAuth2ClientCredentials;
import io.github.authzenenforcer.spring.internal.ReactiveOAuth2ClientCredentials;
import io.github.authzenenforcer.spring.internal.UnenforcedAnnotationsCheck;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.System.Logger.Level;
import java.lang.reflect.Method;

/**
 * Auto-configuration of an {@link AuthZenClient} and an {@link Enforcer} from
 * {@link AuthZenProperties}.
 *
 * <p>Each bean is created only if the application defines none of its type, so that a bean of the
 * application replaces both the default and the properties that configure it. The client sends the
 * credential of a {@link CredentialProvider} bean, uses a {@link JsonCodec} bean if there is one,
 * and reports to every {@link PepListener} bean, as does the enforcer. A {@link PepTracer} bean is
 * the client's tracer rather than one of its listeners.
 *
 * <p>With {@code authzen.pdp.client-registration-id}, the credential provider is a client
 * registration of Spring Security's OAuth 2.0 client with the client credentials grant.
 */
@AutoConfiguration
@EnableConfigurationProperties(AuthZenProperties.class)
public class AuthZenAutoConfiguration {

    private static final System.Logger LOGGER = System.getLogger(AuthZenAutoConfiguration.class.getName());

    private static final String REACTIVE_OAUTH2_CLIENT = "reactor.core.publisher.Mono";
    private static final String OAUTH2_CLIENT =
            "org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager";

    /**
     * Creates the auto-configuration.
     */
    public AuthZenAutoConfiguration() {
        // Instantiated by Spring
    }

    /**
     * Creates the client of the PDP.
     *
     * @param properties  the properties
     * @param credentials the credential provider, if there is one
     * @param jsonCodec   the JSON codec, if the application defines one
     * @param tracer      the tracer, if there is one
     * @param listeners   the listeners
     * @param beans       the bean factory
     * @return the client
     * @throws IllegalStateException if {@code authzen.pdp.url} is not set, or if
     *                               {@code authzen.pdp.client-registration-id} is set without Spring
     *                               Security's OAuth 2.0 client
     */
    @Bean
    @ConditionalOnMissingBean
    public AuthZenClient authZenClient(AuthZenProperties properties, ObjectProvider<CredentialProvider> credentials,
            ObjectProvider<JsonCodec> jsonCodec, ObjectProvider<PepTracer> tracer, ObjectProvider<PepListener> listeners,
            ConfigurableListableBeanFactory beans) {
        AuthZenProperties.Pdp pdp = properties.getPdp();
        String url = pdp.getUrl();
        // Starting without it would leave every protected endpoint without a decision
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("authzen.pdp.url must be set to the base URL of the PDP");
        }
        AuthZenClient.Builder builder = AuthZenClient.builder()
                .pdpUrl(url)
                .connectTimeout(pdp.getConnectTimeout())
                .requestTimeout(pdp.getRequestTimeout())
                .discovery(pdp.isDiscovery());
        if (pdp.getMaxRetries() != null) {
            builder.maxRetries(pdp.getMaxRetries());
        }
        // Two candidates fail the startup instead of leaving the client without one
        CredentialProvider credentialProvider = credentials.getIfAvailable();
        if (pdp.getClientRegistrationId() != null) {
            checkClientRegistration(credentialProvider, pdp.getClientRegistrationId(), beans.getBeanClassLoader());
        }
        if (credentialProvider != null) {
            builder.credentials(credentialProvider);
        }
        JsonCodec codec = jsonCodec.getIfAvailable();
        if (codec != null) {
            builder.jsonCodec(codec);
        }
        // The client registers its tracer as a listener after the others, so that it ends the spans last
        PepTracer pepTracer = tracer.getIfAvailable();
        if (pepTracer != null) {
            builder.tracer(pepTracer);
        }
        listeners.orderedStream().filter(listener -> listener != pepTracer).forEach(builder::listener);
        return builder.build();
    }

    /**
     * Creates the mapping of HTTP methods to actions.
     *
     * @return the default mapper
     */
    @Bean
    @ConditionalOnMissingBean
    public ActionMapper authZenActionMapper() {
        return ActionMapper.defaults();
    }

    /**
     * Creates the inference of resources from route patterns.
     *
     * @param properties the properties
     * @return the inferrer
     */
    @Bean
    @ConditionalOnMissingBean
    public ResourceInferrer authZenResourceInferrer(AuthZenProperties properties) {
        AuthZenProperties.Resource resource = properties.getEnforcer().getResource();
        ResourceInferrer.Builder builder = ResourceInferrer.builder()
                .singularize(resource.isSingularize())
                .maxIdLength(resource.getMaxIdLength());
        resource.getTypeNames().forEach(builder::typeName);
        return builder.build();
    }

    /**
     * Creates the inference of subjects from the caller's claims.
     *
     * @param properties the properties
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public SubjectResolver authZenSubjectResolver(AuthZenProperties properties) {
        AuthZenProperties.Subject subject = properties.getEnforcer().getSubject();
        SubjectResolver.Builder builder = subject.getProfile() == AuthZenProperties.SubjectProfile.KEYCLOAK
                ? SubjectResolver.keycloak()
                : SubjectResolver.claims();
        builder.idClaim(subject.getIdClaim()).type(subject.getType());
        subject.getProperties().forEach((name, path) -> builder.property(name, claimPath(name, path)));
        return builder.build();
    }

    /**
     * Creates the overrides of single routes; none by default.
     *
     * @return the overrides
     */
    @Bean
    @ConditionalOnMissingBean
    public RouteOverrides authZenRouteOverrides() {
        return RouteOverrides.none();
    }

    /**
     * Creates the policy for requests without a decision.
     *
     * @param properties the properties
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean
    public EnforcementPolicy authZenEnforcementPolicy(AuthZenProperties properties) {
        return EnforcementPolicy.builder()
                .errorStatus(properties.getEnforcer().getErrorStatus())
                .failOpen(properties.getEnforcer().isFailOpen())
                .build();
    }

    /**
     * Creates the inference of evaluation requests from its components.
     *
     * @param properties       the properties
     * @param actionMapper     the action mapper
     * @param resourceInferrer the resource inferrer
     * @param subjectResolver  the subject resolver
     * @param overrides        the route overrides
     * @return the inferrer
     */
    @Bean
    @ConditionalOnMissingBean
    public RequestInferrer authZenRequestInferrer(AuthZenProperties properties, ActionMapper actionMapper,
            ResourceInferrer resourceInferrer, SubjectResolver subjectResolver, RouteOverrides overrides) {
        RequestInferrer.Builder builder = RequestInferrer.builder()
                .actionMapper(actionMapper)
                .resourceInferrer(resourceInferrer)
                .subjectResolver(subjectResolver)
                .overrides(overrides);
        AuthZenProperties.Anonymous anonymous = properties.getEnforcer().getAnonymous();
        if (anonymous.isEnabled()) {
            builder.anonymousSubject(Subject.of(anonymous.getType(), anonymous.getId()));
        }
        return builder.build();
    }

    /**
     * Creates the enforcer.
     *
     * @param client    the client
     * @param inferrer  the request inferrer
     * @param policy    the policy
     * @param listeners the listeners
     * @return the enforcer
     */
    @Bean
    @ConditionalOnMissingBean
    public Enforcer authZenEnforcer(AuthZenClient client, RequestInferrer inferrer, EnforcementPolicy policy,
            ObjectProvider<PepListener> listeners) {
        Enforcer.Builder builder = Enforcer.builder(client).inferrer(inferrer).policy(policy);
        listeners.orderedStream().forEach(builder::listener);
        return builder.build();
    }

    /**
     * Creates the check that warns at startup about {@link CheckPermission} annotations that
     * nothing enforces.
     *
     * @param beans    the bean factory
     * @param enforced the types a web integration enforces, if there is one
     * @return the check
     */
    @Bean
    public UnenforcedAnnotationsCheck authZenUnenforcedAnnotationsCheck(ConfigurableListableBeanFactory beans,
            ObjectProvider<EnforcedHandlerTypes> enforced) {
        return new UnenforcedAnnotationsCheck(beans, enforced);
    }

    // Without a credential the PDP rejects every request, so a registration that cannot be used
    // fails the startup
    private static void checkClientRegistration(CredentialProvider credentialProvider, String registrationId,
            ClassLoader classLoader) {
        if (registrationId.isBlank()) {
            throw blankRegistrationId();
        }
        if (!ClassUtils.isPresent(OAUTH2_CLIENT, classLoader)) {
            throw new IllegalStateException("authzen.pdp.client-registration-id needs Spring Security's OAuth 2.0"
                    + " client: add spring-boot-starter-security-oauth2-client");
        }
        if (credentialProvider == null) {
            // Such as the value false, which turns off the condition of the provider
            throw new IllegalStateException("authzen.pdp.client-registration-id is set to '" + registrationId
                    + "', but configured no credential: it must name a registration under"
                    + " spring.security.oauth2.client.registration");
        }
        if (!(credentialProvider instanceof AbstractOAuth2ClientCredentials)) {
            LOGGER.log(Level.WARNING, "authzen.pdp.client-registration-id is ignored: the CredentialProvider bean of"
                    + " the application supplies the PEP's credential");
        }
    }

    private static IllegalStateException blankRegistrationId() {
        return new IllegalStateException("authzen.pdp.client-registration-id must name a registration under"
                + " spring.security.oauth2.client.registration");
    }

    private static String[] claimPath(String name, String path) {
        String[] members = path.split("\\.", -1);
        for (String member : members) {
            if (member.isEmpty()) {
                throw new IllegalStateException("authzen.enforcer.subject.properties." + name
                        + " must be a claim name or a dotted path of claim names");
            }
        }
        return members;
    }

    /** The PEP's token from Spring Security's OAuth 2.0 client. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = OAUTH2_CLIENT)
    @ConditionalOnProperty(prefix = "authzen.pdp", name = "client-registration-id")
    static class OAuth2ClientConfiguration {

        // Not the application's OAuth2AuthorizedClientManager: Spring Security's default one needs
        // the current HTTP request, which the client's own threads do not have
        @Bean
        @ConditionalOnMissingBean
        CredentialProvider authZenOAuth2ClientCredentials(AuthZenProperties properties,
                ObjectProvider<ClientRegistrationRepository> registrations,
                ObjectProvider<OAuth2AuthorizedClientService> clients,
                ObjectProvider<OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>> tokenClient,
                ConfigurableListableBeanFactory beans) {
            String registrationId = properties.getPdp().getClientRegistrationId();
            if (registrationId.isBlank()) {
                throw blankRegistrationId();
            }
            ClientRegistrationRepository repository = registrations.getIfAvailable();
            // Spring Boot creates only the reactive repository in a reactive web application
            if (repository == null && ClassUtils.isPresent(REACTIVE_OAUTH2_CLIENT, beans.getBeanClassLoader())) {
                CredentialProvider reactive = ReactiveOAuth2Client.credentials(registrationId, properties, beans);
                if (reactive != null) {
                    return reactive;
                }
            }
            checkRegistration(registrationId, repository == null
                    ? null
                    : repository.findByRegistrationId(registrationId));
            OAuth2AuthorizedClientService service =
                    clients.getIfAvailable(() -> new InMemoryOAuth2AuthorizedClientService(repository));
            AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                    new AuthorizedClientServiceOAuth2AuthorizedClientManager(repository, service);
            // As Spring Security does for its own manager, so that the client authentication can be changed
            OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> responseClient =
                    tokenClient.getIfAvailable();
            if (responseClient != null) {
                manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials(grant -> grant.accessTokenResponseClient(responseClient))
                        .build());
            }
            // As long as a request waits for its credential at most
            return new OAuth2ClientCredentials(registrationId, manager, service,
                    properties.getPdp().getRequestTimeout());
        }
    }

    // Without one, the PDP rejects every request, so a registration that cannot be used fails the startup
    private static void checkRegistration(String registrationId, ClientRegistration registration) {
        if (registration == null) {
            throw new IllegalStateException("authzen.pdp.client-registration-id names '" + registrationId
                    + "', but there is no such registration under spring.security.oauth2.client.registration");
        }
        if (!AuthorizationGrantType.CLIENT_CREDENTIALS.equals(registration.getAuthorizationGrantType())) {
            throw new IllegalStateException("The OAuth 2.0 client registration '" + registrationId
                    + "' of authzen.pdp.client-registration-id must use the client_credentials grant, not "
                    + registration.getAuthorizationGrantType().getValue());
        }
    }

    /** The reactive OAuth 2.0 client, in a class of its own, so that the configuration loads without Reactor. */
    private static final class ReactiveOAuth2Client {

        private ReactiveOAuth2Client() {
        }

        // As the servlet one: not the application's manager, whose default needs the current exchange
        static CredentialProvider credentials(String registrationId, AuthZenProperties properties,
                ConfigurableListableBeanFactory beans) {
            ReactiveClientRegistrationRepository repository =
                    beans.getBeanProvider(ReactiveClientRegistrationRepository.class).getIfAvailable();
            if (repository == null) {
                return null;
            }
            // Spring Boot's repository holds the registrations in memory; another one is waited for
            // as long as a request waits for its credential
            checkRegistration(registrationId, repository.findByRegistrationId(registrationId)
                    .block(properties.getPdp().getRequestTimeout()));
            ReactiveOAuth2AuthorizedClientService service = beans
                    .getBeanProvider(ReactiveOAuth2AuthorizedClientService.class)
                    .getIfAvailable(() -> new InMemoryReactiveOAuth2AuthorizedClientService(repository));
            AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
                    new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(repository, service);
            ReactiveOAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> responseClient =
                    tokenClient(beans);
            if (responseClient != null) {
                manager.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials(grant -> grant.accessTokenResponseClient(responseClient))
                        .build());
            }
            return new ReactiveOAuth2ClientCredentials(registrationId, manager, service,
                    properties.getPdp().getRequestTimeout());
        }

        // Resolved as Spring injects the servlet token client, which also matches a bean whose type
        // arguments are unknown, such as a lambda
        @SuppressWarnings("unchecked")
        private static ReactiveOAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenClient(
                ConfigurableListableBeanFactory beans) {
            Method injectionPoint = ReflectionUtils.findMethod(ReactiveOAuth2Client.class, "injectionPoint",
                    ObjectProvider.class);
            Object provider = injectionPoint == null
                    ? null
                    : beans.resolveDependency(new DependencyDescriptor(new MethodParameter(injectionPoint, 0), false),
                            null);
            return provider instanceof ObjectProvider<?> tokenClients
                    ? (ReactiveOAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>) tokenClients
                            .getIfAvailable()
                    : null;
        }

        // Only its parameter type is used
        @SuppressWarnings("unused")
        private static void injectionPoint(
                ObjectProvider<ReactiveOAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>> tokenClient) {
        }
    }
}
