/**
 * Root package of the AuthZEN Enforcer core module, holding the exception hierarchy.
 *
 * <p>{@link io.github.authzenenforcer.core.AuthZenException} is the unchecked base class. Every
 * subclass signals that no decision could be obtained, so callers must treat it as a deny.
 */
package io.github.authzenenforcer.core;
