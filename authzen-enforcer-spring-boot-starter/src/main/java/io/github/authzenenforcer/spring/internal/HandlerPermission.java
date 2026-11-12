package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.PermissionSpec;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.ast.Literal;
import org.springframework.expression.spel.standard.SpelExpression;

import java.util.Map;
import java.util.function.Predicate;

/**
 * What enforcement a handler method has: none, its annotations, or enforce-all.
 */
public final class HandlerPermission {

    /** How a handler came to be enforced. */
    enum Source {

        /** Not enforced. */
        NONE,

        /** By {@code @CheckPermission}, which exclude-paths do not apply to. */
        ANNOTATION,

        /** By enforce-all, unless its route is excluded. */
        ENFORCE_ALL
    }

    // Stands in for a resource id expression, whose value is only known per request
    private static final String EXPRESSION_PLACEHOLDER = "expression";

    private final String handler;
    private final Source source;
    private final PermissionSpec literals;
    private final Expression resourceId;
    private final Map<String, Expression> context;
    private final Predicate<String> excluded;
    private final int maxIdLength;

    HandlerPermission(String handler, Source source, PermissionSpec literals, Expression resourceId,
            Map<String, Expression> context, Predicate<String> excluded, int maxIdLength) {
        this.handler = handler;
        this.source = source;
        this.literals = literals;
        this.resourceId = resourceId;
        this.context = context;
        this.excluded = excluded;
        this.maxIdLength = maxIdLength;
    }

    /**
     * Returns whether requests to a route of the handler are enforced.
     *
     * @param routePattern the route pattern, or {@code null} if unknown
     * @return {@code true} if they are
     */
    public boolean enforces(String routePattern) {
        switch (source) {
            case ANNOTATION:
                return true;
            case ENFORCE_ALL:
                // Without a route pattern there is nothing to exclude, so the request is enforced
                return routePattern == null || !excluded.test(routePattern);
            default:
                return false;
        }
    }

    /**
     * Returns the explicit values for checking the handler's routes at startup, with a placeholder
     * for a resource id that an expression gives.
     *
     * @return the values
     */
    public PermissionSpec staticSpec() {
        if (resourceId == null) {
            return literals;
        }
        return PermissionSpec.builder().resourceId(EXPRESSION_PLACEHOLDER).build().orElse(literals);
    }

    /**
     * Returns whether the handler sets the resource type explicitly.
     *
     * @return {@code true} if it does
     */
    public boolean setsResourceType() {
        return literals.getResourceType().isPresent();
    }

    /**
     * Returns whether the resource id comes from the request, through an expression that is not a
     * literal.
     *
     * @return {@code true} if it does
     */
    public boolean takesResourceIdFromRequest() {
        return resourceId instanceof SpelExpression expression && !(expression.getAST() instanceof Literal);
    }

    /**
     * Returns the explicit values for a request.
     *
     * @param variables the variables of the expressions
     * @return the values
     * @throws ExpressionFailure if an expression fails or gives an unusable value
     */
    public PermissionSpec spec(EvaluationContext variables) {
        if (resourceId == null && context.isEmpty()) {
            return literals;
        }
        PermissionSpec.Builder spec = PermissionSpec.builder();
        if (resourceId != null) {
            spec.resourceId(resourceId(evaluate("resourceId", resourceId, variables)));
        }
        context.forEach((name, expression) -> spec.context(name, evaluate("context " + name, expression, variables)));
        try {
            return spec.build().orElse(literals);
        } catch (IllegalArgumentException e) {
            // The message names the type of the value, never the value
            throw new ExpressionFailure("A context expression of " + handler + " gave a value that is not"
                    + " JSON-compatible: " + e.getMessage());
        }
    }

    // The checks that path variables get in the core: the value comes from the caller as well
    private String resourceId(Object value) {
        if (!(value instanceof String || value instanceof Number)) {
            throw new ExpressionFailure("The resourceId expression of " + handler + " gave "
                    + (value == null ? "null" : "a " + value.getClass().getName()) + " instead of a string");
        }
        String id = value.toString();
        String problem = id.isEmpty() ? "an empty id"
                : id.length() > maxIdLength ? "an id longer than " + maxIdLength + " characters"
                : id.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL) ? "an id with a control character"
                : null;
        if (problem != null) {
            throw new ExpressionFailure("The resourceId expression of " + handler + " gave " + problem);
        }
        return id;
    }

    private Object evaluate(String attribute, Expression expression, EvaluationContext variables) {
        try {
            return expression.getValue(variables);
        } catch (RuntimeException e) {
            // Messages of expressions may quote values of the request, so only the type is named
            throw new ExpressionFailure("The " + attribute + " expression of " + handler + " failed with "
                    + e.getClass().getName(), e);
        }
    }

    /**
     * Returns the handler method, for messages.
     *
     * @return the handler
     */
    public String getHandler() {
        return handler;
    }

    @Override
    public String toString() {
        return "HandlerPermission{" + handler + ", " + source + ", " + literals + "}";
    }

    /** An expression of a handler that failed for a request; the request is refused. */
    public static final class ExpressionFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ExpressionFailure(String message) {
            super(message);
        }

        ExpressionFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
