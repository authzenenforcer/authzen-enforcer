package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.spring.CheckPermission;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.Set;

/**
 * Warns at startup about beans annotated with {@link CheckPermission} that nothing enforces: beans
 * that are not Spring MVC controllers, and every bean of an application without Spring MVC
 * enforcement, such as a WebFlux application.
 */
public final class UnenforcedAnnotationsCheck implements SmartInitializingSingleton {

    private static final System.Logger LOGGER = System.getLogger(UnenforcedAnnotationsCheck.class.getName());

    private final ConfigurableListableBeanFactory beans;
    private final ObjectProvider<EnforcedHandlerTypes> enforced;

    /**
     * Creates the check.
     *
     * @param beans    the bean factory
     * @param enforced the types a web integration enforces, if there is one
     */
    public UnenforcedAnnotationsCheck(ConfigurableListableBeanFactory beans,
            ObjectProvider<EnforcedHandlerTypes> enforced) {
        this.beans = beans;
        this.enforced = enforced;
    }

    @Override
    public void afterSingletonsInstantiated() {
        EnforcedHandlerTypes integration = enforced.getIfAvailable();
        Set<Class<?>> handlers = integration != null ? integration.types() : Set.of();
        String why = integration != null
                ? "only handler methods of Spring MVC controllers are enforced"
                : "this application has no Spring MVC (servlet) enforcement; WebFlux is not supported yet";
        Arrays.stream(beans.getBeanDefinitionNames())
                .map(this::type)
                .filter(type -> type != null && !type.getName().startsWith("org.springframework."))
                .map(ClassUtils::getUserClass)
                .distinct()
                .filter(type -> !handlers.contains(type) && annotated(type))
                .forEach(type -> LOGGER.log(Level.WARNING, "@CheckPermission on " + type.getName()
                        + " is not enforced: " + why));
    }

    private Class<?> type(String name) {
        try {
            return beans.getType(name, false);
        } catch (RuntimeException e) {
            // A bean whose type cannot be determined without creating it is not checked
            return null;
        }
    }

    private static boolean annotated(Class<?> type) {
        if (AnnotatedElementUtils.hasAnnotation(type, CheckPermission.class)) {
            return true;
        }
        return Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS))
                .anyMatch(method -> AnnotatedElementUtils.hasAnnotation(method, CheckPermission.class));
    }
}
