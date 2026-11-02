package io.github.authzenenforcer.core.internal.client;

import java.net.URI;
import java.util.Locale;

/** URL checks and derivations shared by the client and the metadata discovery. */
final class Urls {

    private Urls() {
    }

    /**
     * Checks that a URL can be used to talk to the PDP.
     *
     * @throws IllegalArgumentException if the URL is not an absolute {@code http(s)} URL without
     *                                  user info, query or fragment
     */
    static URI check(URI url, String name) {
        String scheme = url.getScheme() == null ? null : url.getScheme().toLowerCase(Locale.ROOT);
        if (!url.isAbsolute() || !("https".equals(scheme) || "http".equals(scheme)) || url.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute http or https URL");
        }
        if (url.getRawUserInfo() != null) {
            throw new IllegalArgumentException(name + " must not contain user info");
        }
        if (url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException(name + " must not contain a query or fragment");
        }
        return url;
    }

    /** Returns the URL without terminating slashes, the form used as PDP identifier. */
    static URI withoutTrailingSlashes(URI url) {
        String path = path(url);
        return path.equals(url.getRawPath()) ? url : URI.create(origin(url) + path);
    }

    /** Appends a path to the path of a base URL. */
    static URI append(URI base, String path) {
        return URI.create(origin(base) + path(base) + path);
    }

    /** Inserts a well-known path between the host and the path of a URL, as RFC 8615 describes. */
    static URI wellKnown(URI identifier, String wellKnownPath) {
        return URI.create(origin(identifier) + wellKnownPath + path(identifier));
    }

    static boolean isHttps(URI url) {
        return "https".equalsIgnoreCase(url.getScheme());
    }

    static boolean isPlainHttpToRemoteHost(URI url) {
        if (!"http".equalsIgnoreCase(url.getScheme())) {
            return false;
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return !(host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1"));
    }

    private static String origin(URI url) {
        return url.getScheme() + "://" + url.getRawAuthority();
    }

    private static String path(URI url) {
        String path = url.getRawPath() == null ? "" : url.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
