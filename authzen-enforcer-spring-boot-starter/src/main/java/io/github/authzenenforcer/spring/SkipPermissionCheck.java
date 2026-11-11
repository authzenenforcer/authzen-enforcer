package io.github.authzenenforcer.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Leaves a handler out of enforcement: out of {@code authzen.enforcer.enforce-all}, and on a
 * method, out of a {@link CheckPermission} on its class.
 *
 * <p>The annotation nearest to the method wins: on a class, it applies to all its handler methods,
 * but a {@link CheckPermission} on a method enforces that method nevertheless. An application with
 * a method or class annotated with both does not start. It may be used as a meta-annotation.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SkipPermissionCheck {
}
