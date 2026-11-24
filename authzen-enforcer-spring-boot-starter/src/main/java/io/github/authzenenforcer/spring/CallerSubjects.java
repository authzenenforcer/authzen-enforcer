package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.spring.internal.SecuritySubjects;

import java.util.Objects;
import java.util.Optional;

import org.springframework.security.core.Authentication;

/**
 * Infers the subject of a caller as the starter's enforcement does, for the application's own calls
 * to the PDP, such as a handler that asks about several documents at once.
 *
 * <pre>{@code
 * @GetMapping("/documents")
 * List<String> readableDocuments(Authentication caller) {
 *     Subject subject = subjects.of(caller).orElseThrow();
 *     ...
 * }
 * }</pre>
 *
 * <p>The subject comes from the same claims, by the same {@link SubjectResolver}, and with the same
 * roles from the granted authorities ({@code authzen.enforcer.subject.authorities}) as the subject of
 * {@code @CheckPermission}, so that the application's requests to the PDP and the starter's agree.
 * Resolving the claims of a token directly, such as {@code Jwt.getClaims()}, would leave out those
 * roles. The starter configures a bean of this type when Spring Security is present.
 *
 * <p>It reads the {@link Authentication} as the starter's claims extractors do and resolves the
 * subject with the starter's {@code RequestInferrer} configuration: with a
 * {@code ClaimsExtractor}, {@code ReactiveClaimsExtractor} or {@code RequestInferrer} bean of the
 * application, the subject of {@code @CheckPermission} can differ from this one.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class CallerSubjects {

    private final SubjectResolver resolver;
    private final boolean authorities;

    CallerSubjects(SubjectResolver resolver, boolean authorities) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.authorities = authorities;
    }

    /**
     * Infers the subject of the caller.
     *
     * @param authentication the caller's authentication, such as a handler's {@link Authentication}
     *                       argument, or {@code null}
     * @return the subject, or empty if the caller is anonymous; the configured anonymous subject of
     *         the enforcement is not used
     * @throws io.github.authzenenforcer.core.enforce.InferenceException if the caller's claims do not
     *         identify a subject; treat it as a deny
     */
    public Optional<Subject> of(Authentication authentication) {
        return SecuritySubjects.of(resolver, authorities, authentication);
    }

    @Override
    public String toString() {
        return "CallerSubjects{resolver=" + resolver + ", authorities=" + authorities + "}";
    }
}
