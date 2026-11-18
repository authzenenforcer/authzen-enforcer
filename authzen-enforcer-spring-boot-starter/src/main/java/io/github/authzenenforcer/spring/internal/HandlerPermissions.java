package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.PermissionSpec;
import io.github.authzenenforcer.spring.CheckPermission;
import io.github.authzenenforcer.spring.SkipPermissionCheck;

import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.http.server.PathContainer;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Finds out, once per handler method, how it is enforced: from {@link CheckPermission} and
 * {@link SkipPermissionCheck} on the method and its class, and from enforce-all.
 */
public final class HandlerPermissions {

    private final boolean enforceAll;
    private final List<PathPattern> excludePaths;
    private final int maxIdLength;
    private final Function<Class<?>, List<Class<?>>> advice;
    // As many entries as there are handler methods and route patterns, which requests cannot add to
    private final Map<Key, HandlerPermission> permissions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> excluded = new ConcurrentHashMap<>();

    /**
     * Creates the permissions.
     *
     * @param enforceAll   whether every handler is enforced
     * @param excludePaths the route patterns that enforce-all leaves out
     * @param maxIdLength  the maximum length of a resource id that an expression gives
     * @throws IllegalStateException if an exclude path is not a valid pattern
     */
    public HandlerPermissions(boolean enforceAll, List<String> excludePaths, int maxIdLength) {
        this(enforceAll, excludePaths, maxIdLength, type -> List.of());
    }

    /**
     * Creates the permissions.
     *
     * @param enforceAll   whether every handler is enforced
     * @param excludePaths the route patterns that enforce-all leaves out
     * @param maxIdLength  the maximum length of a resource id that an expression gives
     * @param advice       the types of the controller advice that applies to a controller type,
     *                     whose {@code @ModelAttribute} methods bind data for its handlers
     * @throws IllegalStateException if an exclude path is not a valid pattern
     */
    public HandlerPermissions(boolean enforceAll, List<String> excludePaths, int maxIdLength,
            Function<Class<?>, List<Class<?>>> advice) {
        this.advice = advice;
        this.enforceAll = enforceAll;
        this.maxIdLength = maxIdLength;
        this.excludePaths = excludePaths.stream().map(HandlerPermissions::pathPattern).toList();
    }

    /**
     * Returns the controller advice of an application context that applies to a controller type,
     * looked up when it is first needed, after the context is refreshed.
     *
     * @param context the application context
     * @return the types of the advice beans that apply to a controller type
     */
    public static Function<Class<?>, List<Class<?>>> controllerAdvice(ApplicationContext context) {
        SingletonSupplier<List<ControllerAdviceBean>> advice =
                SingletonSupplier.of(() -> ControllerAdviceBean.findAnnotatedBeans(context));
        return type -> advice.obtain().stream()
                .filter(bean -> bean.isApplicableToBeanType(type))
                .<Class<?>>map(ControllerAdviceBean::getBeanType)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Returns how a handler method is enforced.
     *
     * @param handler the handler method
     * @return the permission
     * @throws IllegalStateException if the handler's annotations are invalid
     */
    public HandlerPermission get(HandlerMethod handler) {
        return permissions.computeIfAbsent(new Key(handler.getBeanType(), handler.getMethod()), key -> resolve(handler));
    }

    private HandlerPermission resolve(HandlerMethod handler) {
        String name = handler.getBeanType().getName() + "." + handler.getMethod().getName();
        CheckPermission onMethod = annotation(handler.getMethod(), name);
        if (onMethod == null && skipped(handler.getMethod())) {
            return none(name);
        }
        Class<?> type = handler.getBeanType();
        CheckPermission onType = annotation(type, type.getName());
        boolean typeSkipped = onType == null && skipped(type);
        Set<String> bindsData = DataBinding.boundArguments(handler.getBeanType(), handler.getMethod(),
                advice.apply(handler.getBeanType()));
        if (onMethod != null) {
            return annotated(name, onMethod, typeSkipped ? null : onType, bindsData);
        }
        if (onType != null) {
            return annotated(name, onType, null, bindsData);
        }
        if (enforceAll && !typeSkipped) {
            return new HandlerPermission(name, HandlerPermission.Source.ENFORCE_ALL, PermissionSpec.none(), null,
                    Map.of(), this::excluded, maxIdLength, bindsData);
        }
        return none(name);
    }

    private static CheckPermission annotation(AnnotatedElement element, String name) {
        CheckPermission check = AnnotatedElementUtils.findMergedAnnotation(element, CheckPermission.class);
        if (check != null && skipped(element)) {
            throw new IllegalStateException(name + " is annotated with both @CheckPermission and"
                    + " @SkipPermissionCheck");
        }
        return check;
    }

    private static boolean skipped(AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(element, SkipPermissionCheck.class);
    }

    private static HandlerPermission none(String name) {
        return new HandlerPermission(name, HandlerPermission.Source.NONE, PermissionSpec.none(), null, Map.of(),
                route -> true, 0, Set.of());
    }

    // The method's values win attribute by attribute, and its context entries by name
    private HandlerPermission annotated(String name, CheckPermission onMethod, CheckPermission onType,
            Set<String> bindsData) {
        PermissionSpec.Builder literals = PermissionSpec.builder();
        String action = attribute(onMethod.action(), onType == null ? "" : onType.action());
        if (!action.isEmpty()) {
            literals.action(action);
        }
        String resourceType = attribute(onMethod.resourceType(), onType == null ? "" : onType.resourceType());
        if (!resourceType.isEmpty()) {
            literals.resourceType(resourceType);
        }
        String subjectType = attribute(onMethod.subjectType(), onType == null ? "" : onType.subjectType());
        if (!subjectType.isEmpty()) {
            literals.subjectType(subjectType);
        }
        String resourceId = attribute(onMethod.resourceId(), onType == null ? "" : onType.resourceId());
        Map<String, Expression> context = new LinkedHashMap<>();
        if (onType != null) {
            context.putAll(context(name, onType.context()));
        }
        context.putAll(context(name, onMethod.context()));
        return new HandlerPermission(name, HandlerPermission.Source.ANNOTATION, literals.build(),
                resourceId.isEmpty() ? null : parse(name, "resourceId", resourceId),
                Collections.unmodifiableMap(context), route -> false, maxIdLength, bindsData);
    }

    private static String attribute(String onMethod, String onType) {
        return onMethod.isEmpty() ? onType : onMethod;
    }

    private static Map<String, Expression> context(String name, String[] entries) {
        Map<String, Expression> context = new LinkedHashMap<>();
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            String key = separator < 0 ? "" : entry.substring(0, separator).trim();
            String expression = separator < 0 ? "" : entry.substring(separator + 1).trim();
            if (key.isEmpty() || expression.isEmpty()) {
                throw new IllegalStateException("The context entry \"" + entry + "\" of " + name
                        + " must have the form name=expression");
            }
            if (context.put(key, parse(name, "context " + key, expression)) != null) {
                throw new IllegalStateException("The context entry " + key + " of " + name + " is given twice");
            }
        }
        return context;
    }

    private static Expression parse(String name, String attribute, String expression) {
        return Expressions.parse("the " + attribute + " expression of " + name, expression);
    }

    private boolean excluded(String routePattern) {
        return excluded.computeIfAbsent(routePattern, route -> {
            PathContainer path = PathContainer.parsePath(route);
            return excludePaths.stream().anyMatch(exclude -> exclude.matches(path));
        });
    }

    // A method inherited by controllers whose classes are annotated differently is enforced differently
    private record Key(Class<?> type, Method method) {
    }

    private static PathPattern pathPattern(String pattern) {
        try {
            return PathPatternParser.defaultInstance.parse(pattern);
        } catch (RuntimeException e) {
            throw new IllegalStateException("authzen.enforcer.exclude-paths contains an invalid pattern: "
                    + pattern, e);
        }
    }
}
