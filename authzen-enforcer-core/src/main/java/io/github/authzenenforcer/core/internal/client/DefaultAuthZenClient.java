package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.internal.http.PdpTransport;
import io.github.authzenenforcer.core.internal.wire.WireFormat;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
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

    private final URI evaluationEndpoint;
    private final PdpTransport transport;

    /**
     * Creates a client. Use {@link AuthZenClient#builder()} instead.
     *
     * @param pdpUrl             the base URL of the PDP
     * @param evaluationEndpoint the evaluation endpoint, or {@code null} for the default path
     * @param credentials        the credential provider, or {@code null}
     * @param codec              the JSON codec
     * @param httpClient         an HTTP client that does not follow redirects
     * @param requestTimeout     the timeout of a single request
     * @param maxRetries         how often a request that failed transiently may be repeated
     */
    public DefaultAuthZenClient(URI pdpUrl, URI evaluationEndpoint, CredentialProvider credentials, JsonCodec codec,
            HttpClient httpClient, Duration requestTimeout, int maxRetries) {
        URI base = checkUrl(pdpUrl, "pdpUrl");
        this.evaluationEndpoint = evaluationEndpoint != null
                ? checkUrl(evaluationEndpoint, "evaluationEndpoint")
                : append(base, EVALUATION_PATH);
        this.transport = new PdpTransport(httpClient, credentials, codec, requestTimeout, maxRetries);
        if (credentials != null && isPlainHttpToRemoteHost(this.evaluationEndpoint)) {
            LOGGER.log(Level.WARNING, "PDP endpoint " + this.evaluationEndpoint
                    + " uses plain HTTP; credentials are sent unencrypted");
        }
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        CompletableFuture<EvaluationResponse> future = evaluateAsync(request);
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PdpCommunicationException("Interrupted while waiting for the PDP", e);
        } catch (ExecutionException e) {
            throw asAuthZenException(e.getCause());
        }
    }

    @Override
    public CompletableFuture<EvaluationResponse> evaluateAsync(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        return transport.post(evaluationEndpoint, WireFormat.evaluationRequest(request))
                .thenApply(WireFormat::evaluationResponse);
    }

    private static AuthZenException asAuthZenException(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof AuthZenException authZenException) {
            return authZenException;
        }
        return new AuthZenException("Evaluation failed unexpectedly", cause);
    }

    private static URI checkUrl(URI url, String name) {
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

    private static URI append(URI base, String path) {
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        return base.resolve(basePath + path);
    }

    private static boolean isPlainHttpToRemoteHost(URI url) {
        if (!"http".equalsIgnoreCase(url.getScheme())) {
            return false;
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return !(host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1"));
    }
}
