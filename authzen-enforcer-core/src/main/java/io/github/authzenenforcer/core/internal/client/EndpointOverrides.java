package io.github.authzenenforcer.core.internal.client;

import java.net.URI;

/**
 * Endpoint URLs set on the client builder. An endpoint that is {@code null} is discovered, or uses
 * its default path below the PDP URL.
 *
 * @param evaluation     the Access Evaluation endpoint
 * @param evaluations    the Access Evaluations endpoint
 * @param searchSubject  the Subject Search endpoint
 * @param searchResource the Resource Search endpoint
 * @param searchAction   the Action Search endpoint
 */
public record EndpointOverrides(URI evaluation, URI evaluations, URI searchSubject, URI searchResource,
        URI searchAction) {
}
