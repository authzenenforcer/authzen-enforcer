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
import io.github.authzenenforcer.spring.internal.EnforcedHandlerTypes;
import io.github.authzenenforcer.spring.internal.UnenforcedAnnotationsCheck;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration of an {@link AuthZenClient} and an {@link Enforcer} from
 * {@link AuthZenProperties}.
 *
 * <p>Each bean is created only if the application defines none of its type, so that a bean of the
 * application replaces both the default and the properties that configure it. The client sends the
 * credential of a {@link CredentialProvider} bean, uses a {@link JsonCodec} bean if there is one,
 * and reports to every {@link PepListener} bean, as does the enforcer.
 */
@AutoConfiguration
@EnableConfigurationProperties(AuthZenProperties.class)
public class AuthZenAutoConfiguration {

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
     * @param credentials the credential provider, if the application defines one
     * @param jsonCodec   the JSON codec, if the application defines one
     * @param listeners   the listeners
     * @return the client
     * @throws IllegalStateException if {@code authzen.pdp.url} is not set
     */
    @Bean
    @ConditionalOnMissingBean
    public AuthZenClient authZenClient(AuthZenProperties properties, ObjectProvider<CredentialProvider> credentials,
            ObjectProvider<JsonCodec> jsonCodec, ObjectProvider<PepListener> listeners) {
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
        if (credentialProvider != null) {
            builder.credentials(credentialProvider);
        }
        JsonCodec codec = jsonCodec.getIfAvailable();
        if (codec != null) {
            builder.jsonCodec(codec);
        }
        listeners.orderedStream().forEach(builder::listener);
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
}
