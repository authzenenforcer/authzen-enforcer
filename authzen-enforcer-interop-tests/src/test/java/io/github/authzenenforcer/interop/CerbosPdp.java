package io.github.authzenenforcer.interop;

import java.net.URI;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Cerbos acting as AuthZEN PDP, with the policies in {@code src/test/resources/cerbos}.
 *
 * <p>By default a container is started once per test run. To test against a Cerbos that is already
 * running with those policies, set {@code AUTHZEN_CERBOS_URL} (for example
 * {@code http://127.0.0.1:3592}). Cerbos's AuthZEN API needs no credentials; see
 * {@code docs/pdps/cerbos.md}.
 */
final class CerbosPdp {

    // compose.yaml is the only place that names the Cerbos version, so Dependabot can update it
    private static final String IMAGE_REPOSITORY = "ghcr.io/cerbos/cerbos";
    private static final String[] POLICIES = {"document.yaml"};
    private static final int HTTP_PORT = 3592;

    private static CerbosPdp instance;

    private final URI url;

    private CerbosPdp(URI url) {
        this.url = url;
    }

    /** Returns the shared PDP, starting the container on first use. */
    static synchronized CerbosPdp get() {
        if (instance == null) {
            String external = System.getenv("AUTHZEN_CERBOS_URL");
            instance = external != null ? new CerbosPdp(withoutTrailingSlash(external)) : container();
        }
        return instance;
    }

    private static URI withoutTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return URI.create(url.substring(0, end));
    }

    @SuppressWarnings("resource") // Testcontainers removes the container when the JVM exits
    private static CerbosPdp container() {
        GenericContainer<?> cerbos = new GenericContainer<>(ComposeImages.of(IMAGE_REPOSITORY))
                .withExposedPorts(HTTP_PORT)
                // Ready once the policies are loaded and the HTTP API answers
                .waitingFor(Wait.forHttp("/_cerbos/health")
                        .forPort(HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        // Cerbos reads the policies in /policies without further configuration
        for (String policy : POLICIES) {
            cerbos.withCopyFileToContainer(MountableFile.forClasspathResource("cerbos/" + policy),
                    "/policies/" + policy);
        }
        cerbos.start();
        return new CerbosPdp(URI.create("http://" + cerbos.getHost() + ":" + cerbos.getMappedPort(HTTP_PORT)));
    }

    /** Returns the URL of the PDP, which is also its {@code policy_decision_point}. */
    URI url() {
        return url;
    }
}
