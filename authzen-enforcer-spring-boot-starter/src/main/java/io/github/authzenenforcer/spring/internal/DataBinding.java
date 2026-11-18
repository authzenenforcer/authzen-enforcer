package io.github.authzenenforcer.spring.internal;

import org.springframework.beans.BeanUtils;
import org.springframework.core.Conventions;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds requests whose values Spring's data binding would take from another source than the one
 * the PDP was asked about.
 *
 * <p>Data binding fills the object of a {@code @ModelAttribute} argument, or of an argument that no
 * other resolver takes, from the request's parameters, multipart parts, URI variables and headers,
 * in that order of precedence, and also from names that only resemble a property's name: another
 * case, a {@code !} or {@code _} prefix, or an index or nested path. So
 * {@code PUT /documents/{id}?id=other} binds {@code other} to the property {@code id}, while the
 * PDP was asked about the path's id. Spring MVC also creates such an argument from a parameter
 * named like the argument, by type conversion. Constructor binding ignores
 * {@code @ModelAttribute(binding = false)}, and the {@code @ModelAttribute} methods of the
 * controller and of its controller advice bind their arguments for the same request. For handlers
 * that bind data, every value the PDP sees, from a path variable, a parameter or a header, must
 * have no other source of the same name, and no value may be named like a bound argument.
 */
final class DataBinding {

    // Annotations whose resolvers take an argument from one source, without data binding
    private static final Set<String> RESOLVED_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestParam",
            "org.springframework.web.bind.annotation.PathVariable",
            "org.springframework.web.bind.annotation.RequestBody",
            "org.springframework.web.bind.annotation.RequestHeader",
            "org.springframework.web.bind.annotation.CookieValue",
            "org.springframework.web.bind.annotation.RequestPart",
            "org.springframework.web.bind.annotation.RequestAttribute",
            "org.springframework.web.bind.annotation.SessionAttribute",
            "org.springframework.web.bind.annotation.MatrixVariable",
            "org.springframework.beans.factory.annotation.Value",
            "org.springframework.security.core.annotation.AuthenticationPrincipal",
            "org.springframework.security.core.annotation.CurrentSecurityContext");

    // Types that Spring MVC and Spring WebFlux resolve by type, or as the model, without data binding
    private static final Set<String> RESOLVED_TYPES = Set.of(
            "jakarta.servlet.ServletRequest", "jakarta.servlet.ServletResponse", "jakarta.servlet.http.HttpSession",
            "jakarta.servlet.http.PushBuilder", "jakarta.servlet.http.Part",
            "java.security.Principal", "java.io.InputStream", "java.io.Reader", "java.io.OutputStream",
            "java.io.Writer", "java.util.Locale", "java.util.TimeZone", "java.time.ZoneId", "java.util.Map",
            "org.springframework.http.HttpMethod", "org.springframework.http.HttpEntity",
            "org.springframework.ui.Model", "org.springframework.validation.Errors",
            "org.springframework.web.bind.support.SessionStatus", "org.springframework.web.util.UriComponentsBuilder",
            "org.springframework.web.context.request.WebRequest", "org.springframework.web.multipart.MultipartRequest",
            "org.springframework.web.multipart.MultipartFile", "org.springframework.web.server.ServerWebExchange",
            "org.springframework.http.server.reactive.ServerHttpRequest",
            "org.springframework.http.server.reactive.ServerHttpResponse",
            "org.springframework.web.server.WebSession", "org.springframework.http.codec.multipart.Part",
            "kotlin.coroutines.Continuation");

    // Wrappers whose element is the argument, such as Mono<Document> for an asynchronous model attribute
    private static final Set<String> WRAPPERS = Set.of("org.reactivestreams.Publisher",
            "java.util.concurrent.CompletionStage", "java.util.Optional");

    private DataBinding() {
    }

    /**
     * Returns whether a handler method may have an argument that data binding fills. Arguments
     * that a resolver of the application takes count as bound unless an annotation above names
     * them, so that no bound argument is missed.
     *
     * @param method the handler method
     * @return {@code true} if an argument may be bound
     */
    static boolean binds(Method method) {
        return !arguments(List.of(method)).isEmpty();
    }

    /**
     * Returns the names of the arguments that data binding may fill for a request to a handler
     * method: its own and those of the {@code @ModelAttribute} methods of its controller and of
     * the controller advice that applies to it.
     *
     * @param beanType the controller type
     * @param method   the handler method
     * @param advice   the types of the controller advice that applies to the controller
     * @return the names, as Spring names model attributes; empty if nothing is bound
     */
    static Set<String> boundArguments(Class<?> beanType, Method method, List<Class<?>> advice) {
        List<Method> methods = new ArrayList<>();
        methods.add(method);
        methods.addAll(modelAttributeMethods(beanType));
        advice.forEach(type -> methods.addAll(modelAttributeMethods(type)));
        return arguments(methods);
    }

    private static Set<Method> modelAttributeMethods(Class<?> type) {
        return MethodIntrospector.selectMethods(type, (ReflectionUtils.MethodFilter) candidate ->
                AnnotatedElementUtils.hasAnnotation(candidate, ModelAttribute.class)
                        && !AnnotatedElementUtils.hasAnnotation(candidate, RequestMapping.class));
    }

    private static Set<String> arguments(List<Method> methods) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : methods) {
            for (int i = 0; i < method.getParameterCount(); i++) {
                MethodParameter parameter = new MethodParameter(method, i);
                if (binds(parameter)) {
                    names.addAll(names(parameter));
                }
            }
        }
        return names;
    }

    // As Spring names the model attribute of the argument, and the name of its element type, in
    // case a wrapper changes it
    private static Set<String> names(MethodParameter parameter) {
        Set<String> names = new LinkedHashSet<>();
        ModelAttribute modelAttribute = parameter.getParameterAnnotation(ModelAttribute.class);
        if (modelAttribute != null) {
            // name and value are aliases, which only a synthesized annotation resolves
            String name = modelAttribute.name().isEmpty() ? modelAttribute.value() : modelAttribute.name();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        try {
            names.add(Conventions.getVariableNameForParameter(parameter));
        } catch (IllegalArgumentException e) {
            // Such as an empty collection type, which cannot be named
        }
        names.add(ClassUtils.getShortNameAsProperty(parameter.nested().getNestedParameterType()));
        return names;
    }

    private static boolean binds(MethodParameter parameter) {
        // Constructor binding ignores binding = false
        if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
            return true;
        }
        for (Annotation annotation : parameter.getParameterAnnotations()) {
            if (RESOLVED_ANNOTATIONS.contains(annotation.annotationType().getName())) {
                return false;
            }
        }
        Class<?> type = parameter.getParameterType();
        if (isA(type, WRAPPERS)) {
            type = parameter.nested().getNestedParameterType();
        }
        // Simple types without an annotation are request parameters
        return !BeanUtils.isSimpleProperty(type) && !isA(type, RESOLVED_TYPES);
    }

    private static boolean isA(Class<?> type, Set<String> names) {
        Deque<Class<?>> types = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        types.add(type);
        while (!types.isEmpty()) {
            Class<?> current = types.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (names.contains(current.getName())) {
                return true;
            }
            if (current.getSuperclass() != null) {
                types.add(current.getSuperclass());
            }
            types.addAll(List.of(current.getInterfaces()));
        }
        return false;
    }

    /**
     * Returns why data binding could take a value the PDP sees from another source, if it could.
     *
     * @param permission    the handler's permission; its handler binds data
     * @param pathVariables the path variables
     * @param valueNames    the names of the request's parameters and multipart parts
     * @param headerNames   the names of the request's headers
     * @return the reason to refuse the request, or {@code null}
     */
    static String conflict(HandlerPermission permission, Map<String, String> pathVariables,
            Collection<String> valueNames, Collection<String> headerNames) {
        String handler = permission.getHandler();
        // Spring MVC creates the argument from a parameter of its name, before data binding
        for (String argument : permission.getBoundArguments()) {
            if (valueNames.stream().anyMatch(name -> resembles(name, argument))) {
                return "A request to " + handler + " has a parameter or part named like the argument " + argument
                        + ", which Spring would create the argument from";
            }
        }
        // Data binding prefers parameters and parts to URI variables, and URI variables to headers
        for (String variable : pathVariables.keySet()) {
            if (valueNames.stream().anyMatch(name -> resembles(name, variable))) {
                return "A request to " + handler + " has a parameter or part named like the path variable "
                        + variable + ", which data binding would use instead";
            }
        }
        for (String parameter : permission.getReadParameters()) {
            boolean other = valueNames.stream().anyMatch(name -> !name.equals(parameter) && resembles(name, parameter));
            boolean absent = !valueNames.contains(parameter);
            if (other || absent && (pathVariables.keySet().stream().anyMatch(parameter::equalsIgnoreCase)
                    || headerNames.stream().anyMatch(header -> headerResembles(header, parameter)))) {
                return "The parameter " + parameter + " that an expression of " + handler
                        + " reads has another source that data binding would use";
            }
        }
        for (String header : permission.getReadHeaders()) {
            String bound = normalize(header);
            if (valueNames.stream().anyMatch(name -> resembles(name, header) || resembles(name, bound))
                    || pathVariables.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(header)
                            || name.equalsIgnoreCase(bound))
                    || headerNames.stream().anyMatch(name -> !name.equalsIgnoreCase(header)
                            && (headerResembles(name, header) || headerResembles(name, bound)))) {
                return "The header " + header + " that an expression of " + handler
                        + " reads has another source that data binding would use";
            }
        }
        return null;
    }

    // Data binding finds a property under another case of its first letter, behind a field marker
    // or default prefix, and as the head of an indexed or nested path
    private static boolean resembles(String name, String property) {
        String head = name.startsWith("!") || name.startsWith("_") ? name.substring(1) : name;
        int end = head.length();
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c == '[' || c == '.') {
                end = i;
                break;
            }
        }
        return head.substring(0, end).equalsIgnoreCase(property);
    }

    // Headers are bound under their own name and under it without dashes and uncapitalized
    private static boolean headerResembles(String header, String property) {
        return header.equalsIgnoreCase(property) || normalize(header).equalsIgnoreCase(property);
    }

    private static String normalize(String header) {
        return header.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
