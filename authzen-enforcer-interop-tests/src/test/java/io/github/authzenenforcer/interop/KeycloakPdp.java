package io.github.authzenenforcer.interop;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak acting as AuthZEN PDP, with the {@code authzen-demo} realm.
 *
 * <p>By default a container is started once per test run. To test against a Keycloak that is
 * already running, set {@code AUTHZEN_KEYCLOAK_URL} (for example {@code http://127.0.0.1:8080}) and
 * {@code AUTHZEN_PEP_CLIENT_SECRET}; the realm must have been imported from
 * {@code src/test/resources/keycloak/authzen-demo-realm.json}.
 *
 * <p>The client secret is never stored in the repository: the realm file reads it from the
 * {@code AUTHZEN_PEP_CLIENT_SECRET} environment variable at import time, and the container gets a
 * random one unless that variable is set.
 */
final class KeycloakPdp {

    static final String REALM = "authzen-demo";
    static final String CLIENT_ID = "authzen-pep";

    // compose.yaml is the only place that names the Keycloak version, so Dependabot can update it
    private static final String COMPOSE_FILE = "compose.yaml";
    private static final Pattern IMAGE_LINE = Pattern.compile("^\\s*image:\\s*(\\S+)\\s*$");
    private static final String SECRET_VARIABLE = "AUTHZEN_PEP_CLIENT_SECRET";
    private static final int HTTP_PORT = 8080;

    private static KeycloakPdp instance;

    private final URI baseUrl;
    private final String clientSecret;

    private KeycloakPdp(URI baseUrl, String clientSecret) {
        this.baseUrl = baseUrl;
        this.clientSecret = clientSecret;
    }

    /** Returns the shared PDP, starting the container on first use. */
    static synchronized KeycloakPdp get() {
        if (instance == null) {
            String external = System.getenv("AUTHZEN_KEYCLOAK_URL");
            instance = external != null ? external(external) : container();
        }
        return instance;
    }

    private static KeycloakPdp external(String url) {
        String secret = System.getenv(SECRET_VARIABLE);
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException(SECRET_VARIABLE + " must be set when AUTHZEN_KEYCLOAK_URL is set");
        }
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return new KeycloakPdp(URI.create(url.substring(0, end)), secret);
    }

    @SuppressWarnings("resource") // Testcontainers removes the container when the JVM exits
    private static KeycloakPdp container() {
        String secret = System.getenv(SECRET_VARIABLE);
        if (secret == null || secret.isEmpty()) {
            secret = randomSecret();
        }
        GenericContainer<?> keycloak = new GenericContainer<>(image())
                .withCommand("start-dev", "--features=authzen", "--import-realm")
                .withEnv(SECRET_VARIABLE, secret)
                .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/authzen-demo-realm.json"),
                        "/opt/keycloak/data/import/authzen-demo-realm.json")
                .withExposedPorts(HTTP_PORT)
                // Ready once the realm is imported and the AuthZEN feature serves its metadata
                .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/authzen-configuration")
                        .forPort(HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        keycloak.start();
        URI baseUrl = URI.create("http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(HTTP_PORT));
        return new KeycloakPdp(baseUrl, secret);
    }

    /** Returns the image of the single service in {@code compose.yaml}, copied to the test classpath. */
    static String image() {
        List<String> images;
        try (InputStream in = KeycloakPdp.class.getClassLoader().getResourceAsStream(COMPOSE_FILE)) {
            if (in == null) {
                throw new IllegalStateException(COMPOSE_FILE + " is not on the test classpath");
            }
            images = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(IMAGE_LINE::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (images.size() != 1) {
            throw new IllegalStateException("expected one image in " + COMPOSE_FILE + ", found " + images);
        }
        return images.get(0);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    URI realmUrl() {
        return URI.create(baseUrl + "/realms/" + REALM);
    }

    URI tokenEndpoint() {
        return URI.create(realmUrl() + "/protocol/openid-connect/token");
    }

    String clientSecret() {
        return clientSecret;
    }
}
