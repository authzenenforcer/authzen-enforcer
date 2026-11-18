package io.github.authzenenforcer.spring.internal;

import java.util.Collection;
import java.util.List;
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
     * Returns the value of a query or form parameter, unless it carries credentials.
     *
     * <p>A parameter with more than one value, repeated or both in the query and in the form, is
     * refused: handlers read such parameters differently, so the value an expression read would not
     * be the one the handler acts on. Spring WebFlux's {@code @RequestParam} reads only the query,
     * while its form data and data binding prefer the body; Spring MVC joins the values for a
     * {@code String} argument.
     *
     * @param name the parameter name
     * @return the value, or {@code null} if there is none or it carries credentials
     * @throws AmbiguousParameterException if the parameter has more than one value
     * @throws MultipartParameterException   if the request is a multipart request
     */
    public final String parameter(String name) {
        if (name == null || CREDENTIAL_PARAMETERS.contains(name)) {
            return null;
        }
        List<String> values = parameterValues(name);
        if (values.size() > 1) {
            throw new AmbiguousParameterException();
        }
        return values.isEmpty() ? null : values.get(0);
    }

    abstract String headerValue(String name);

    // The names of the query and form parameters and of the multipart parts, as data binding sees them
    abstract Collection<String> valueNames();

    abstract Collection<String> headerNames();

    // The values of the query and then of the form; throws MultipartParameterException for a multipart
    // request, whose parts a handler can read by name although they are no parameters
    abstract List<String> parameterValues(String name);

    static boolean isMultipart(String contentType) {
        return contentType != null && contentType.trim().toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    @Override
    public String toString() {
        return "RequestView";
    }

    /**
     * An expression read a parameter of a multipart request; the request is refused. Parts that
     * carry a file name are no parameters, yet a handler can read them by name, as with
     * {@code @RequestPart}.
     */
    public static final class MultipartParameterException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MultipartParameterException() {
            super("Parameters of multipart requests are not read", null, false, false);
        }
    }

    /** A parameter that an expression read has more than one value; the request is refused. */
    public static final class AmbiguousParameterException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AmbiguousParameterException() {
            super("The parameter has more than one value", null, false, false);
        }
    }
}
