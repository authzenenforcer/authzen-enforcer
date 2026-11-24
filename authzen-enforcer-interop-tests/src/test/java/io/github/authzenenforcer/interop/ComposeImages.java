package io.github.authzenenforcer.interop;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the container images of the PDPs from {@code compose.yaml}, the only place that names their
 * versions, so that Dependabot can update them.
 */
final class ComposeImages {

    private static final String COMPOSE_FILE = "compose.yaml";
    private static final Pattern IMAGE_LINE = Pattern.compile("^\\s*image:\\s*(\\S+)\\s*$");

    private ComposeImages() {
    }

    /**
     * Returns the image of the one service in {@code compose.yaml}, copied to the test classpath,
     * whose image comes from the repository.
     *
     * @param repository the repository, such as {@code quay.io/keycloak/keycloak}
     * @return the image with its tag
     */
    static String of(String repository) {
        List<String> images;
        try (InputStream in = ComposeImages.class.getClassLoader().getResourceAsStream(COMPOSE_FILE)) {
            if (in == null) {
                throw new IllegalStateException(COMPOSE_FILE + " is not on the test classpath");
            }
            images = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(IMAGE_LINE::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1))
                    .filter(image -> image.startsWith(repository + ":"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (images.size() != 1) {
            throw new IllegalStateException("expected one image of " + repository + " in " + COMPOSE_FILE
                    + ", found " + images);
        }
        return images.get(0);
    }
}
