package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.internal.http.PdpTransport;
import io.github.authzenenforcer.core.internal.wire.WireFormat;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Default {@link AuthZenClient} on top of {@link java.net.http.HttpClient}.
 */
public final class DefaultAuthZenClient implements AuthZenClient {

    private static final System.Logger LOGGER = System.getLogger(DefaultAuthZenClient.class.getName());

    /** Default path of the Access Evaluation endpoint below the PDP URL. */
    static final String EVALUATION_PATH = "/access/v1/evaluation";

    private final URI defaultEvaluationEndpoint;
    private final URI evaluationEndpoint;
    private final MetadataResolver metadata;
    private final PdpTransport transport;

    /**
     * Creates a client. Use {@link AuthZenClient#builder()} instead.
     *
     * @param pdpUrl                  the base URL of the PDP, also its identifier in the metadata
     * @param evaluationEndpoint      the evaluation endpoint, or {@code null} to discover it or use
     *                                the default path
     * @param credentials             the credential provider, or {@code null}
     * @param codec                   the JSON codec
     * @param httpClient              an HTTP client that does not follow redirects
     * @param requestTimeout          the timeout of a single request
     * @param maxRetries              how often a request that failed transiently may be repeated
     * @param discovery               whether endpoints are discovered from the PDP metadata
     * @param metadataRefreshInterval how long discovered metadata is used before it is fetched again
     */
    public DefaultAuthZenClient(URI pdpUrl, URI evaluationEndpoint, CredentialProvider credentials, JsonCodec codec,
            HttpClient httpClient, Duration requestTimeout, int maxRetries, boolean discovery,
            Duration metadataRefreshInterval) {
        URI identifier = Urls.withoutTrailingSlashes(Urls.check(pdpUrl, "pdpUrl"));
        this.defaultEvaluationEndpoint = Urls.append(identifier, EVALUATION_PATH);
        this.evaluationEndpoint = evaluationEndpoint != null ? Urls.check(evaluationEndpoint, "evaluationEndpoint") : null;
        this.transport = new PdpTransport(httpClient, credentials, codec, requestTimeout, maxRetries);
        this.metadata = discovery
                ? new MetadataResolver(transport, identifier, metadataRefreshInterval, credentials != null)
                : null;
        URI configured = this.evaluationEndpoint != null ? this.evaluationEndpoint : identifier;
        if (credentials != null && Urls.isPlainHttpToRemoteHost(configured)) {
            LOGGER.log(Level.WARNING, "PDP endpoint " + configured + " uses plain HTTP; credentials are sent unencrypted");
        }
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        return await(evaluateAsync(request), "Evaluation");
    }

    @Override
    public CompletableFuture<EvaluationResponse> evaluateAsync(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = WireFormat.evaluationRequest(request);
        return evaluationEndpoint()
                .thenCompose(endpoint -> transport.post(endpoint, body))
                .thenApply(WireFormat::evaluationResponse);
    }

    @Override
    public Optional<PdpMetadata> metadata() {
        return await(metadataAsync(), "Metadata discovery");
    }

    @Override
    public CompletableFuture<Optional<PdpMetadata>> metadataAsync() {
        return metadata != null ? metadata.metadata() : CompletableFuture.completedFuture(Optional.empty());
    }

    private CompletableFuture<URI> evaluationEndpoint() {
        if (evaluationEndpoint != null) {
            return CompletableFuture.completedFuture(evaluationEndpoint);
        }
        return metadataAsync().thenApply(current -> current
                .map(PdpMetadata::getAccessEvaluationEndpoint)
                .orElse(defaultEvaluationEndpoint));
    }

    private static <T> T await(CompletableFuture<T> future, String operation) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PdpCommunicationException("Interrupted while waiting for the PDP", e);
        } catch (ExecutionException e) {
            throw asAuthZenException(e.getCause(), operation);
        }
    }

    private static AuthZenException asAuthZenException(Throwable error, String operation) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof AuthZenException authZenException) {
            return authZenException;
        }
        return new AuthZenException(operation + " failed unexpectedly", cause);
    }
}
