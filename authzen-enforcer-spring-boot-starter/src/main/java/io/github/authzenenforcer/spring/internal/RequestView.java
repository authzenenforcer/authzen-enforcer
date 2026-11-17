package io.github.authzenenforcer.spring.internal;

import java.util.Locale;
import java.util.Set;

/**
 * The request as expressions see it: the {@code #request} variable. It exposes a few values and
 * nothing that carries credentials, so that an expression cannot put them into an evaluation
 * request.
 */
public abstract class RequestView {

    // Headers that carry credentials or session identifiers
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "dpop");
    // The form and query parameter of RFC 6750
    private static final Set<String> CREDENTIAL_PARAMETERS = Set.of("access_token");

    // Only the views of this package
    RequestView() {
    }

    /**
     * Returns the HTTP method.
     *
     * @return the method
     */
    public abstract String getMethod();

    /**
     * Returns the address of the client or of the last proxy.
     *
     * @return the address
     */
    public abstract String getRemoteAddr();

    /**
     * Returns the first value of a header, unless it carries credentials.
     *
     * @param name the header name
     * @return the value, or {@code null} if there is none or it carries credentials
     */
    public final String header(String name) {
        return name == null || CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? null : headerValue(name);
    }

    /**
     * Returns the first value of a query or form parameter, unless it carries credentials.
     *
     * @param name the parameter name
     * @return the value, or {@code null} if there is none or it carries credentials
     */
    public final String parameter(String name) {
        return name == null || CREDENTIAL_PARAMETERS.contains(name) ? null : parameterValue(name);
    }

    abstract String headerValue(String name);

    abstract String parameterValue(String name);

    @Override
    public String toString() {
        return "RequestView";
    }
}
