package io.github.authzenenforcer.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Asks the PDP whether a request to a handler may proceed, before the handler runs.
 *
 * <p>Every attribute is optional: what is not given is inferred from the request, as described in
 * {@link io.github.authzenenforcer.core.enforce.RequestInferrer}. The action from the HTTP method,
 * the resource from the route pattern and its path variables, the subject from the authenticated
 * caller. An empty value means "not given".
 *
 * <pre>{@code
 * @CheckPermission(action = "cancel", resourceType = "order", resourceId = "#id")
 * @PostMapping("/orders/{id}/cancel")
 * void cancel(@PathVariable String id) { ... }
 * }</pre>
 *
 * <p>On a class, the annotation applies to all its handler methods. An annotation on the method
 * overrides the one on the class attribute by attribute, and context entries by name. It may be
 * used as a meta-annotation.
 *
 * <p>{@link #resourceId()} and the values of {@link #context()} are SpEL expressions. They may
 * read only these, and an application whose expressions use anything else does not start:
 * <ul>
 *   <li>the path variables by name, such as {@code #id};</li>
 *   <li>{@code #request.method}, {@code #request.remoteAddr}, {@code #request.header(name)} and
 *       {@code #request.parameter(name)}; headers and parameters that carry credentials, such as
 *       {@code Authorization}, {@code Cookie} or {@code access_token}, read as {@code null};</li>
 *   <li>{@code #claims}, the claims the subject is inferred from, such as {@code #claims['sub']}
 *       or {@code #claims.realm_access.roles}; {@code null} for an anonymous caller.</li>
 * </ul>
 * Types, constructors, beans, other methods and assignments are not available, so that an
 * expression cannot reach credentials or change state. The request body is not available: the
 * check runs before it is read. A request whose expressions fail, or whose resource id is not a
 * non-empty string or number within {@code authzen.enforcer.resource.max-id-length} and without
 * control characters, is denied without asking the PDP.
 *
 * <p>Only handler methods of Spring MVC controllers are enforced. The annotation on any other
 * bean, or in an application without Spring MVC, such as a WebFlux application, has no effect and
 * is reported as a warning at startup.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CheckPermission {

    /**
     * The action name, such as {@code approve}. Inferred from the HTTP method if empty.
     *
     * @return the action
     */
    String action() default "";

    /**
     * The resource type, such as {@code document}. Inferred from the route pattern if empty.
     *
     * @return the resource type
     */
    String resourceType() default "";

    /**
     * A SpEL expression for the resource id, such as {@code #id}. Inferred from the route pattern
     * if empty.
     *
     * @return the expression
     */
    String resourceId() default "";

    /**
     * The subject type, such as {@code user}. Inferred from the caller if empty.
     *
     * @return the subject type
     */
    String subjectType() default "";

    /**
     * Entries of the evaluation context, each as {@code name=expression}, such as
     * {@code "ip=#request.remoteAddr"}. A value must evaluate to a JSON-compatible value.
     *
     * @return the entries
     */
    String[] context() default {};
}
