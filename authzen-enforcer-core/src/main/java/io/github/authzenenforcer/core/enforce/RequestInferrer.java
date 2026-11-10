package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.internal.text.LogText;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a request into an AuthZEN evaluation request.
 *
 * <p>Each value comes from the first of these that has it:
 * <ol>
 *   <li>the explicit values of the endpoint, passed as a {@link PermissionSpec};</li>
 *   <li>the {@link RouteOverrides} configured for the route;</li>
 *   <li>inference: the {@link ActionMapper} for the action, the {@link ResourceInferrer} for the
 *       resource, the {@link SubjectResolver} for the subject.</li>
 * </ol>
 * An explicit resource type or id alone replaces that part of the inferred resource; on a
 * collection route, where the inferred resource is {@code {type: "collection", id: "documents"}},
 * either one replaces the id, which names what the collection holds. The context holds the members
 * of the spec and the override; nothing is added by default.
 *
 * <p>A request is {@linkplain Inference.Kind#NOT_ENFORCED not enforced} if the action mapper
 * exempts its method and no action is set explicitly. An anonymous caller is
 * {@linkplain Inference.Kind#UNAUTHENTICATED unauthenticated} unless an
 * {@linkplain Builder#anonymousSubject(Subject) anonymous subject} is configured. Whatever cannot be
 * inferred safely is {@linkplain Inference.Kind#REFUSED refused}; a refusal caused by the endpoint's
 * configuration is logged as a warning once per reason, one caused by the request's input at debug
 * level. {@link #check(String, String, PermissionSpec)} finds misconfigured routes at startup.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RequestInferrer {

    private static final System.Logger LOGGER = System.getLogger(RequestInferrer.class.getName());
    // Reasons name routes and claims, not values, so there are only as many as there are routes
    private static final int MAX_WARNED = 1000;

    private final ActionMapper actionMapper;
    private final ResourceInferrer resourceInferrer;
    private final SubjectResolver subjectResolver;
    private final RouteOverrides overrides;
    private final Subject anonymousSubject;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private RequestInferrer(Builder builder) {
        this.actionMapper = builder.actionMapper;
        this.resourceInferrer = builder.resourceInferrer;
        this.subjectResolver = builder.subjectResolver;
        this.overrides = builder.overrides;
        this.anonymousSubject = builder.anonymousSubject;
    }

    /**
     * Returns an inferrer with the default components and no overrides.
     *
     * @return the inferrer
     */
    public static RequestInferrer defaults() {
        return builder().build();
    }

    /**
     * Returns a new builder with the default components.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Infers the evaluation request for a request.
     *
     * @param request the request
     * @param spec    the explicit values of the endpoint
     * @return the inference; never throws for a request that cannot be inferred
     */
    public Inference infer(RequestDescription request, PermissionSpec spec) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        String method = request.getMethod();
        PermissionSpec explicit = spec.orElse(overrides.forRoute(method, request.getRoutePattern()));
        if (explicit.getAction().isEmpty() && !actionMapper.enforces(method)) {
            return Inference.notEnforced();
        }
        Optional<Map<String, Object>> claims = request.getClaims();
        if (claims.isEmpty() && anonymousSubject == null) {
            return Inference.unauthenticated();
        }
        try {
            InferredResource inferred = resource(request, explicit);
            String action = explicit.getAction()
                    .or(() -> actionMapper.action(method, inferred.isCollection()))
                    .orElseThrow(() -> new InferenceException("No action is known for method " + LogText.of(method)
                            + " of route " + LogText.of(request.getRoutePattern())
                            + "; set the action explicitly or map the method", true));
            Subject subject = claims.isPresent() ? subjectResolver.resolve(claims.get()) : anonymousSubject;
            if (explicit.getSubjectType().isPresent()) {
                subject = Subject.builder()
                        .type(explicit.getSubjectType().get())
                        .id(subject.getId())
                        .properties(subject.getProperties())
                        .build();
            }
            EvaluationRequest.Builder evaluation = EvaluationRequest.builder()
                    .subject(subject)
                    .action(Action.of(action))
                    .resource(inferred.getResource());
            if (!explicit.getContext().isEmpty()) {
                evaluation.context(Context.of(explicit.getContext()));
            }
            LOGGER.log(Level.DEBUG, () -> "Inferred action " + LogText.of(action) + " on a "
                    + LogText.of(inferred.getResource().getType()) + " for " + LogText.of(method) + " "
                    + LogText.of(request.getRoutePattern()));
            return Inference.evaluate(evaluation.build());
        } catch (InferenceException e) {
            log(e);
            return Inference.refused(e);
        }
    }

    /**
     * Checks, before any request arrives, whether requests to a route can be inferred. Framework
     * integrations call this at startup for every protected route and log what it returns. It
     * finds problems of the route itself; invalid input of a request, such as an empty path
     * variable, and claims without a subject id only show at request time.
     *
     * @param method       the HTTP method
     * @param routePattern the route pattern
     * @param spec         the explicit values of the endpoint
     * @return why requests to the route will be refused, or empty if they can be inferred
     */
    public Optional<String> check(String method, String routePattern, PermissionSpec spec) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(routePattern, "routePattern must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        PermissionSpec explicit = spec.orElse(overrides.forRoute(method, routePattern));
        if (explicit.getAction().isEmpty() && !actionMapper.enforces(method)) {
            return Optional.empty();
        }
        try {
            if (explicit.getResourceType().isEmpty() || explicit.getResourceId().isEmpty()) {
                resourceInferrer.check(method, routePattern, explicit.getAction().isPresent());
            }
            if (explicit.getAction().isEmpty() && actionMapper.action(method, false).isEmpty()
                    && actionMapper.action(method, true).isEmpty()) {
                return Optional.of("No action is known for method " + LogText.of(method) + " of route "
                        + LogText.of(routePattern) + "; set the action explicitly or map the method");
            }
            return Optional.empty();
        } catch (InferenceException e) {
            return Optional.of(e.getMessage());
        }
    }

    private InferredResource resource(RequestDescription request, PermissionSpec explicit) {
        Optional<String> type = explicit.getResourceType();
        Optional<String> id = explicit.getResourceId();
        if (type.isPresent() && id.isPresent()) {
            return InferredResource.single(Resource.of(type.get(), id.get()));
        }
        InferredResource inferred = resourceInferrer.infer(request, explicit.getAction().isPresent());
        if (type.isEmpty() && id.isEmpty()) {
            return inferred;
        }
        Resource resource = inferred.getResource();
        if (inferred.isCollection()) {
            // A collection's id is the type of what it holds, which an explicit type or id names
            return InferredResource.collection(Resource.builder()
                    .type(resource.getType())
                    .id(type.or(() -> id).get())
                    .properties(resource.getProperties())
                    .build());
        }
        return InferredResource.single(Resource.builder()
                .type(type.orElse(resource.getType()))
                .id(id.orElse(resource.getId()))
                .properties(resource.getProperties())
                .build());
    }

    private void log(InferenceException e) {
        if (!e.isMisconfiguration()) {
            LOGGER.log(Level.DEBUG, () -> "Denied without asking the PDP: " + e.getMessage());
        } else if (warned.size() < MAX_WARNED && warned.add(e.getMessage())) {
            LOGGER.log(Level.WARNING, "Denied without asking the PDP: " + e.getMessage()
                    + " (logged once; later occurrences at debug level)");
        } else {
            LOGGER.log(Level.DEBUG, () -> "Denied without asking the PDP: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "RequestInferrer{actionMapper=" + actionMapper + ", resourceInferrer=" + resourceInferrer
                + ", subjectResolver=" + subjectResolver + ", overrides=" + overrides
                + ", anonymousSubject=" + (anonymousSubject != null) + "}";
    }

    /**
     * Builder for {@link RequestInferrer}, starting from the default components. Not thread-safe.
     */
    public static final class Builder {

        private ActionMapper actionMapper = ActionMapper.defaults();
        private ResourceInferrer resourceInferrer = ResourceInferrer.defaults();
        private SubjectResolver subjectResolver = SubjectResolver.claims().build();
        private RouteOverrides overrides = RouteOverrides.none();
        private Subject anonymousSubject;

        private Builder() {
        }

        /**
         * Sets how the action is inferred. Defaults to {@link ActionMapper#defaults()}.
         *
         * @param actionMapper the mapper
         * @return this builder
         */
        public Builder actionMapper(ActionMapper actionMapper) {
            this.actionMapper = Objects.requireNonNull(actionMapper, "actionMapper must not be null");
            return this;
        }

        /**
         * Sets how the resource is inferred. Defaults to {@link ResourceInferrer#defaults()}.
         *
         * @param resourceInferrer the inferrer
         * @return this builder
         */
        public Builder resourceInferrer(ResourceInferrer resourceInferrer) {
            this.resourceInferrer = Objects.requireNonNull(resourceInferrer, "resourceInferrer must not be null");
            return this;
        }

        /**
         * Sets how the subject is inferred. Defaults to {@code SubjectResolver.claims().build()}.
         *
         * @param subjectResolver the resolver
         * @return this builder
         */
        public Builder subjectResolver(SubjectResolver subjectResolver) {
            this.subjectResolver = Objects.requireNonNull(subjectResolver, "subjectResolver must not be null");
            return this;
        }

        /**
         * Sets the values configured per route.
         *
         * @param overrides the overrides
         * @return this builder
         */
        public Builder overrides(RouteOverrides overrides) {
            this.overrides = Objects.requireNonNull(overrides, "overrides must not be null");
            return this;
        }

        /**
         * Asks the PDP about anonymous callers as this subject, instead of denying them without
         * asking. Not set by default.
         *
         * @param anonymousSubject the subject, such as {@code Subject.of("anonymous", "anonymous")}
         * @return this builder
         */
        public Builder anonymousSubject(Subject anonymousSubject) {
            this.anonymousSubject = Objects.requireNonNull(anonymousSubject, "anonymousSubject must not be null");
            return this;
        }

        /**
         * Builds the inferrer.
         *
         * @return the inferrer
         */
        public RequestInferrer build() {
            return new RequestInferrer(this);
        }
    }
}
