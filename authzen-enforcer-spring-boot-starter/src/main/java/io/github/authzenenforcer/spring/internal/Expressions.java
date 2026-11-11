package io.github.authzenenforcer.spring.internal;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.ParseException;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.Assign;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.ConstructorReference;
import org.springframework.expression.spel.ast.FunctionReference;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.TypeReference;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and evaluates the expressions of {@code @CheckPermission}.
 *
 * <p>Expressions see only an allow-list: path variables, {@code #request} as a {@link RequestView}
 * and {@code #claims}, the caller's claims. The evaluation context resolves no other property or
 * method, and expressions that use any are rejected when they are parsed, so that a mistake shows
 * at startup rather than as refused requests.
 */
final class Expressions {

    static final String REQUEST = "request";
    static final String CLAIMS = "claims";

    private static final Set<String> REQUEST_PROPERTIES = Set.of("method", "remoteAddr");
    private static final Set<String> REQUEST_METHODS = Set.of("header", "parameter");
    private static final String ALLOWED = "expressions may only read path variables, #request.method,"
            + " #request.remoteAddr, #request.header(name), #request.parameter(name) and #claims";

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final PropertyAccessor REQUEST_ACCESSOR = new RequestAccessor();
    private static final MethodResolver REQUEST_RESOLVER = new RequestResolver();
    private static final PropertyAccessor CLAIMS_ACCESSOR = new MapAccessor(false);

    private Expressions() {
    }

    /**
     * Parses an expression and checks that it uses only what expressions may read.
     *
     * @param where      names the expression in messages, such as "the resourceId expression of X"
     * @param expression the expression text
     * @return the expression
     * @throws IllegalStateException if the expression is invalid or uses anything else
     */
    static Expression parse(String where, String expression) {
        SpelExpression parsed;
        try {
            parsed = PARSER.parseRaw(expression);
        } catch (ParseException e) {
            throw new IllegalStateException(capitalize(where) + " is invalid: " + e.getMessage(), e);
        }
        check(where, parsed.getAST(), null, 0);
        return parsed;
    }

    /**
     * Creates the variables of the expressions for a request.
     *
     * @param pathVariables the path variables
     * @param request       the request
     * @param claims        the caller's claims, or {@code null} for an anonymous caller
     * @return the read-only context
     */
    static EvaluationContext context(Map<String, String> pathVariables, RequestView request,
            Map<String, Object> claims) {
        SimpleEvaluationContext context = SimpleEvaluationContext
                .forPropertyAccessors(REQUEST_ACCESSOR, CLAIMS_ACCESSOR)
                .withMethodResolvers(REQUEST_RESOLVER)
                .withAssignmentDisabled()
                .build();
        pathVariables.forEach(context::setVariable);
        // Set last, so that a path variable cannot stand in for them
        context.setVariable(REQUEST, request);
        context.setVariable(CLAIMS, claims);
        return context;
    }

    // head is the first element of the compound expression the node is in, index its position there
    private static void check(String where, SpelNode node, String head, int index) {
        if (node instanceof TypeReference || node instanceof ConstructorReference || node instanceof BeanReference
                || node instanceof Assign || node instanceof FunctionReference) {
            throw rejected(where, node);
        }
        if (node instanceof MethodReference method && !(REQUEST.equals(head) && index == 1
                && REQUEST_METHODS.contains(method.getName()))) {
            throw rejected(where, node);
        }
        if (node instanceof PropertyOrFieldReference property && !CLAIMS.equals(head)
                && !(REQUEST.equals(head) && index == 1 && REQUEST_PROPERTIES.contains(property.getName()))) {
            throw rejected(where, node);
        }
        if (node instanceof CompoundExpression) {
            String first = node.getChild(0) instanceof VariableReference variable ? name(variable) : "";
            for (int i = 0; i < node.getChildCount(); i++) {
                SpelNode child = node.getChild(i);
                // Within #claims, any member and index; elsewhere, an index needs a #claims head too
                if (child instanceof Indexer && !CLAIMS.equals(first) && i > 0) {
                    throw rejected(where, child);
                }
                check(where, child, first, i);
            }
            return;
        }
        // Arguments and nested expressions start their own chains
        for (int i = 0; i < node.getChildCount(); i++) {
            check(where, node.getChild(i), null, 0);
        }
    }

    private static String name(VariableReference variable) {
        return variable.toStringAST().substring(1);
    }

    private static IllegalStateException rejected(String where, SpelNode node) {
        return new IllegalStateException(capitalize(where) + " uses " + node.toStringAST() + ", but " + ALLOWED);
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Reads the allowed properties of {@code #request}. */
    private static final class RequestAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[] {RequestView.class};
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof RequestView && REQUEST_PROPERTIES.contains(name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
            RequestView request = (RequestView) target;
            switch (name) {
                case "method":
                    return new TypedValue(request.getMethod());
                case "remoteAddr":
                    return new TypedValue(request.getRemoteAddr());
                default:
                    throw new AccessException("No such property of #request: " + name);
            }
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object value)
                throws AccessException {
            throw new AccessException("#request is read-only");
        }
    }

    /** Resolves the allowed methods of {@code #request}. */
    private static final class RequestResolver implements MethodResolver {

        @Override
        public MethodExecutor resolve(EvaluationContext context, Object target, String name,
                List<TypeDescriptor> argumentTypes) {
            if (!(target instanceof RequestView) || !REQUEST_METHODS.contains(name) || argumentTypes.size() != 1) {
                return null;
            }
            return (evaluation, view, arguments) -> {
                String argument = arguments[0] == null ? null : arguments[0].toString();
                RequestView request = (RequestView) view;
                return new TypedValue("header".equals(name) ? request.header(argument) : request.parameter(argument));
            };
        }
    }
}
