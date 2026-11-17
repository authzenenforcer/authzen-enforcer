/**
 * Enforcement in Spring WebFlux: a handler adapter that asks the PDP before
 * {@link io.github.authzenenforcer.spring.CheckPermission} handlers run, and the components that
 * an application may replace with beans of its own.
 */
package io.github.authzenenforcer.spring.reactive;
