/**
 * Framework-independent enforcement: decides whether an HTTP request may proceed.
 *
 * <p>An {@link io.github.authzenenforcer.core.enforce.Enforcer} infers the request's AuthZEN
 * evaluation request, asks the PDP and returns an {@link
 * io.github.authzenenforcer.core.enforce.EnforcementResult}, applying an {@link
 * io.github.authzenenforcer.core.enforce.EnforcementPolicy} to requests without a decision.
 *
 * <p>Framework integrations describe the request with a {@link
 * io.github.authzenenforcer.core.enforce.RequestDescription}, using the route pattern the framework
 * matched rather than the raw request URI, and pass the explicit values of the endpoint as a {@link
 * io.github.authzenenforcer.core.enforce.PermissionSpec}. A {@link
 * io.github.authzenenforcer.core.enforce.RequestInferrer} infers what is missing: the action from the
 * HTTP method, the resource from the route, the subject from the caller's claims.
 *
 * <p>Whatever is inferred can be set explicitly. An explicit value of the endpoint wins over a
 * configured {@link io.github.authzenenforcer.core.enforce.RouteOverrides route override}, which
 * wins over inference, and every inference step is a replaceable component.
 *
 * <p>Inference fails closed: a route whose resource cannot be inferred safely, an invalid path
 * variable or a caller without a usable identity yields no evaluation request, and the request is
 * denied without asking the PDP. {@link
 * io.github.authzenenforcer.core.enforce.RequestInferrer#check(String, String, PermissionSpec)}
 * finds such routes before any request arrives.
 */
package io.github.authzenenforcer.core.enforce;
