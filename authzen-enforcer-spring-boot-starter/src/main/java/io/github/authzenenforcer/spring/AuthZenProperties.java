package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.core.enforce.ResourceInferrer;
import io.github.authzenenforcer.core.enforce.SubjectResolver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties of authzen-enforcer, under the prefix {@code authzen}.
 *
 * <p>Only {@code authzen.pdp.url} is required. A bean of the corresponding type replaces the
 * properties that configure it, for example a {@link SubjectResolver} bean the
 * {@code authzen.enforcer.subject.*} properties.
 */
@ConfigurationProperties("authzen")
public class AuthZenProperties {

    private final Pdp pdp = new Pdp();
    private final Enforcer enforcer = new Enforcer();
    private final Metrics metrics = new Metrics();
    private final Tracing tracing = new Tracing();

    /**
     * Returns the properties of the PDP connection.
     *
     * @return the properties
     */
    public Pdp getPdp() {
        return pdp;
    }

    /**
     * Returns the properties of enforcement.
     *
     * @return the properties
     */
    public Enforcer getEnforcer() {
        return enforcer;
    }

    /**
     * Returns the properties of the Micrometer metrics.
     *
     * @return the properties
     */
    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * Returns the properties of the OpenTelemetry traces.
     *
     * @return the properties
     */
    public Tracing getTracing() {
        return tracing;
    }

    /**
     * Properties of the PDP connection, under {@code authzen.pdp}.
     */
    public static class Pdp {

        /**
         * Base URL of the PDP, such as https://pdp.example.com. Required: the application does not
         * start without it, unless it defines an AuthZenClient bean of its own.
         */
        private String url;

        /** Timeout for establishing a connection to the PDP. */
        private Duration connectTimeout = AuthZenClient.Builder.DEFAULT_CONNECT_TIMEOUT;

        /** Timeout of a call to the PDP, including its retries. */
        private Duration requestTimeout = AuthZenClient.Builder.DEFAULT_REQUEST_TIMEOUT;

        /** Maximum number of retries of a call that failed transiently, from 0 to 10. */
        private Integer maxRetries;

        /** Whether to discover the endpoints from the PDP's metadata. */
        private boolean discovery = true;

        /**
         * Registration of Spring Security's OAuth 2.0 client, under
         * spring.security.oauth2.client.registration, whose client credentials grant obtains the
         * PEP's token for the PDP. A CredentialProvider bean of the application takes precedence.
         */
        private String clientRegistrationId;

        /**
         * Returns the base URL of the PDP.
         *
         * @return the URL, or {@code null} if not set
         */
        public String getUrl() {
            return url;
        }

        /**
         * Sets the base URL of the PDP.
         *
         * @param url the URL
         */
        public void setUrl(String url) {
            this.url = url;
        }

        /**
         * Returns the connect timeout.
         *
         * @return the timeout
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * Sets the connect timeout.
         *
         * @param connectTimeout the timeout
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * Returns the request timeout.
         *
         * @return the timeout
         */
        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        /**
         * Sets the request timeout.
         *
         * @param requestTimeout the timeout
         */
        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        /**
         * Returns the maximum number of retries.
         *
         * @return the number, or {@code null} for the client's default
         */
        public Integer getMaxRetries() {
            return maxRetries;
        }

        /**
         * Sets the maximum number of retries.
         *
         * @param maxRetries the number
         */
        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        /**
         * Returns whether endpoints are discovered from the PDP's metadata.
         *
         * @return {@code true} if they are
         */
        public boolean isDiscovery() {
            return discovery;
        }

        /**
         * Sets whether endpoints are discovered from the PDP's metadata.
         *
         * @param discovery whether they are
         */
        public void setDiscovery(boolean discovery) {
            this.discovery = discovery;
        }

        /**
         * Returns the registration of Spring Security's OAuth 2.0 client that obtains the PEP's
         * token.
         *
         * @return the registration id, or {@code null} if not set
         */
        public String getClientRegistrationId() {
            return clientRegistrationId;
        }

        /**
         * Sets the registration of Spring Security's OAuth 2.0 client that obtains the PEP's token.
         *
         * @param clientRegistrationId the registration id
         */
        public void setClientRegistrationId(String clientRegistrationId) {
            this.clientRegistrationId = clientRegistrationId;
        }
    }

    /**
     * Properties of enforcement, under {@code authzen.enforcer}.
     */
    public static class Enforcer {

        /**
         * Whether to enforce every handler method, not only those annotated with @CheckPermission.
         * Handlers annotated with @SkipPermissionCheck, and routes matching exclude-paths, are left
         * out.
         */
        private boolean enforceAll;

        /**
         * Route patterns that enforce-all leaves out, such as /public/**. They are matched against
         * the route pattern of a handler, not against the request path. They do not apply to
         * handlers annotated with @CheckPermission.
         */
        private List<String> excludePaths = new ArrayList<>();

        /** HTTP status of a request for which the PDP gave no decision: 403 or 503. */
        private int errorStatus = EnforcementResult.FORBIDDEN;

        /**
         * Whether to let requests through when the PDP is unavailable. Applies to the whole
         * application and is logged; a deny of the PDP still denies.
         */
        private boolean failOpen;

        private final Anonymous anonymous = new Anonymous();
        private final Subject subject = new Subject();
        private final Resource resource = new Resource();

        /**
         * Returns whether every handler is enforced.
         *
         * @return {@code true} if every handler is
         */
        public boolean isEnforceAll() {
            return enforceAll;
        }

        /**
         * Sets whether every handler is enforced.
         *
         * @param enforceAll whether every handler is
         */
        public void setEnforceAll(boolean enforceAll) {
            this.enforceAll = enforceAll;
        }

        /**
         * Returns the route patterns that enforce-all leaves out.
         *
         * @return the patterns
         */
        public List<String> getExcludePaths() {
            return excludePaths;
        }

        /**
         * Sets the route patterns that enforce-all leaves out.
         *
         * @param excludePaths the patterns
         */
        public void setExcludePaths(List<String> excludePaths) {
            this.excludePaths = excludePaths;
        }

        /**
         * Returns the status of a request without a decision.
         *
         * @return 403 or 503
         */
        public int getErrorStatus() {
            return errorStatus;
        }

        /**
         * Sets the status of a request without a decision.
         *
         * @param errorStatus 403 or 503
         */
        public void setErrorStatus(int errorStatus) {
            this.errorStatus = errorStatus;
        }

        /**
         * Returns whether requests are let through when the PDP is unavailable.
         *
         * @return {@code true} for fail-open
         */
        public boolean isFailOpen() {
            return failOpen;
        }

        /**
         * Sets whether requests are let through when the PDP is unavailable.
         *
         * @param failOpen whether they are
         */
        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        /**
         * Returns the properties of anonymous callers.
         *
         * @return the properties
         */
        public Anonymous getAnonymous() {
            return anonymous;
        }

        /**
         * Returns the properties of the subject.
         *
         * @return the properties
         */
        public Subject getSubject() {
            return subject;
        }

        /**
         * Returns the properties of the resource.
         *
         * @return the properties
         */
        public Resource getResource() {
            return resource;
        }
    }

    /**
     * Properties of anonymous callers, under {@code authzen.enforcer.anonymous}.
     */
    public static class Anonymous {

        /**
         * Whether to ask the PDP about anonymous callers with the subject below. Otherwise they are
         * answered with 401 without asking it.
         */
        private boolean enabled;

        /** Type of the subject of anonymous callers. */
        private String type = "anonymous";

        /** Id of the subject of anonymous callers. */
        private String id = "anonymous";

        /**
         * Returns whether anonymous callers are evaluated.
         *
         * @return {@code true} if they are
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether anonymous callers are evaluated.
         *
         * @param enabled whether they are
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns the subject type of anonymous callers.
         *
         * @return the type
         */
        public String getType() {
            return type;
        }

        /**
         * Sets the subject type of anonymous callers.
         *
         * @param type the type
         */
        public void setType(String type) {
            this.type = type;
        }

        /**
         * Returns the subject id of anonymous callers.
         *
         * @return the id
         */
        public String getId() {
            return id;
        }

        /**
         * Sets the subject id of anonymous callers.
         *
         * @param id the id
         */
        public void setId(String id) {
            this.id = id;
        }
    }

    /** Built-in sets of subject properties. */
    public enum SubjectProfile {

        /** No properties beyond those mapped explicitly; the profile for Keycloak as the PDP. */
        DEFAULT,

        /**
         * The roles and groups of a Keycloak access token, and type client for service accounts,
         * for Keycloak as the identity provider and another PDP. Keycloak as the PDP denies such
         * service accounts.
         */
        KEYCLOAK
    }

    /**
     * Properties of the subject, under {@code authzen.enforcer.subject}.
     */
    public static class Subject {

        /** Built-in set of subject properties. */
        private SubjectProfile profile = SubjectProfile.DEFAULT;

        /** Claim that holds the subject id. */
        private String idClaim = SubjectResolver.Builder.DEFAULT_ID_CLAIM;

        /** Subject type. */
        private String type = SubjectResolver.Builder.DEFAULT_TYPE;

        /**
         * Claims copied into subject properties, by property name. A claim nested in objects is
         * named by a dotted path, such as realm_access.roles.
         */
        private Map<String, String> properties = new LinkedHashMap<>();

        /**
         * Returns the profile.
         *
         * @return the profile
         */
        public SubjectProfile getProfile() {
            return profile;
        }

        /**
         * Sets the profile.
         *
         * @param profile the profile
         */
        public void setProfile(SubjectProfile profile) {
            this.profile = profile;
        }

        /**
         * Returns the claim that holds the subject id.
         *
         * @return the claim name
         */
        public String getIdClaim() {
            return idClaim;
        }

        /**
         * Sets the claim that holds the subject id.
         *
         * @param idClaim the claim name
         */
        public void setIdClaim(String idClaim) {
            this.idClaim = idClaim;
        }

        /**
         * Returns the subject type.
         *
         * @return the type
         */
        public String getType() {
            return type;
        }

        /**
         * Sets the subject type.
         *
         * @param type the type
         */
        public void setType(String type) {
            this.type = type;
        }

        /**
         * Returns the claims copied into subject properties.
         *
         * @return dotted claim paths by property name
         */
        public Map<String, String> getProperties() {
            return properties;
        }

        /**
         * Sets the claims copied into subject properties.
         *
         * @param properties dotted claim paths by property name
         */
        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }
    }

    /**
     * Properties of the resource, under {@code authzen.enforcer.resource}.
     */
    public static class Resource {

        /** Whether to turn plural route segments into singular resource types. */
        private boolean singularize;

        /** Maximum length of a resource id taken from a path variable; longer ones are denied. */
        private int maxIdLength = ResourceInferrer.Builder.DEFAULT_MAX_ID_LENGTH;

        /** Resource types by route segment, such as analyses: analysis. */
        private Map<String, String> typeNames = new LinkedHashMap<>();

        /**
         * Resource type of a collection route, such as GET /documents; the id is the type of its
         * resources. Use a type that no single resource has.
         */
        private String collectionType = ResourceInferrer.Builder.DEFAULT_COLLECTION_TYPE;

        /**
         * Returns whether route segments are singularized.
         *
         * @return {@code true} if they are
         */
        public boolean isSingularize() {
            return singularize;
        }

        /**
         * Sets whether route segments are singularized.
         *
         * @param singularize whether they are
         */
        public void setSingularize(boolean singularize) {
            this.singularize = singularize;
        }

        /**
         * Returns the maximum length of a resource id.
         *
         * @return the length
         */
        public int getMaxIdLength() {
            return maxIdLength;
        }

        /**
         * Sets the maximum length of a resource id.
         *
         * @param maxIdLength the length
         */
        public void setMaxIdLength(int maxIdLength) {
            this.maxIdLength = maxIdLength;
        }

        /**
         * Returns the resource type of collection routes.
         *
         * @return the type
         */
        public String getCollectionType() {
            return collectionType;
        }

        /**
         * Sets the resource type of collection routes.
         *
         * @param collectionType the type
         */
        public void setCollectionType(String collectionType) {
            this.collectionType = collectionType;
        }

        /**
         * Returns the resource types by route segment.
         *
         * @return the types
         */
        public Map<String, String> getTypeNames() {
            return typeNames;
        }

        /**
         * Sets the resource types by route segment.
         *
         * @param typeNames the types
         */
        public void setTypeNames(Map<String, String> typeNames) {
            this.typeNames = typeNames;
        }
    }

    /**
     * Properties of the Micrometer metrics, under {@code authzen.metrics}. They apply when
     * authzen-enforcer-micrometer is present and the application has a MeterRegistry bean.
     */
    public static class Metrics {

        /** Whether to record metrics of the client and the enforcer. */
        private boolean enabled = true;

        /**
         * Whether to tag authzen.client.calls with resource.type; only for few and fixed resource
         * types.
         */
        private boolean resourceTypeTag;

        /** Whether to tag authzen.client.calls with action.name; only for few and fixed action names. */
        private boolean actionNameTag;

        /**
         * Returns whether metrics are recorded.
         *
         * @return {@code true} if they are
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether metrics are recorded.
         *
         * @param enabled whether they are
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns whether calls are tagged with the resource type.
         *
         * @return {@code true} if they are
         */
        public boolean isResourceTypeTag() {
            return resourceTypeTag;
        }

        /**
         * Sets whether calls are tagged with the resource type.
         *
         * @param resourceTypeTag whether they are
         */
        public void setResourceTypeTag(boolean resourceTypeTag) {
            this.resourceTypeTag = resourceTypeTag;
        }

        /**
         * Returns whether calls are tagged with the action name.
         *
         * @return {@code true} if they are
         */
        public boolean isActionNameTag() {
            return actionNameTag;
        }

        /**
         * Sets whether calls are tagged with the action name.
         *
         * @param actionNameTag whether they are
         */
        public void setActionNameTag(boolean actionNameTag) {
            this.actionNameTag = actionNameTag;
        }
    }

    /**
     * Properties of the OpenTelemetry traces, under {@code authzen.tracing}. They apply when
     * authzen-enforcer-opentelemetry is present and the application has an OpenTelemetry bean.
     */
    public static class Tracing {

        /** Whether to trace the calls to the PDP and send their trace context to it. */
        private boolean enabled = true;

        /** Whether spans carry authzen.resource.type. */
        private boolean resourceTypeAttribute = true;

        /** Whether spans carry authzen.action.name. */
        private boolean actionNameAttribute = true;

        /**
         * Returns whether calls are traced.
         *
         * @return {@code true} if they are
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether calls are traced.
         *
         * @param enabled whether they are
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns whether spans carry the resource type.
         *
         * @return {@code true} if they do
         */
        public boolean isResourceTypeAttribute() {
            return resourceTypeAttribute;
        }

        /**
         * Sets whether spans carry the resource type.
         *
         * @param resourceTypeAttribute whether they do
         */
        public void setResourceTypeAttribute(boolean resourceTypeAttribute) {
            this.resourceTypeAttribute = resourceTypeAttribute;
        }

        /**
         * Returns whether spans carry the action name.
         *
         * @return {@code true} if they do
         */
        public boolean isActionNameAttribute() {
            return actionNameAttribute;
        }

        /**
         * Sets whether spans carry the action name.
         *
         * @param actionNameAttribute whether they do
         */
        public void setActionNameAttribute(boolean actionNameAttribute) {
            this.actionNameAttribute = actionNameAttribute;
        }
    }
}
