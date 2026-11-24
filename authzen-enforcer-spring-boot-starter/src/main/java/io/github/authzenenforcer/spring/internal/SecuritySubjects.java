package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Subject;

import java.util.Optional;

import org.springframework.security.core.Authentication;

/**
 * Infers the subject of a Spring Security {@link Authentication} as the starter's enforcement does,
 * for {@link io.github.authzenenforcer.spring.CallerSubjects}.
 */
public final class SecuritySubjects {

    private SecuritySubjects() {
    }

    /**
     * Infers the subject.
     *
     * @param resolver       the resolver of the enforcement
     * @param authorities    whether the caller's granted authorities go to the resolver
     * @param authentication the authentication, or {@code null}
     * @return the subject, or empty if the caller is anonymous
     */
    public static Optional<Subject> of(SubjectResolver resolver, boolean authorities, Authentication authentication) {
        return SecurityClaims.of(authentication, authorities).map(resolver::resolve);
    }
}
