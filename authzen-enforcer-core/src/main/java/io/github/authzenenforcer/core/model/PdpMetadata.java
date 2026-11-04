package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.text.LogText;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The configuration a PDP publishes at {@code /.well-known/authzen-configuration}.
 *
 * <p>Serialized as the AuthZEN metadata document:
 * <pre>{@code
 * {"policy_decision_point": "https://pdp.example.com",
 *  "access_evaluation_endpoint": "https://pdp.example.com/access/v1/evaluation"}
 * }</pre>
 *
 * <p>An absent optional endpoint means that the PDP does not support that API. Signed metadata
 * ({@code signed_metadata}) is not supported; the specification allows a PEP to ignore it.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class PdpMetadata {

    private final String policyDecisionPoint;
    private final URI accessEvaluationEndpoint;
    private final URI accessEvaluationsEndpoint;
    private final URI searchSubjectEndpoint;
    private final URI searchResourceEndpoint;
    private final URI searchActionEndpoint;
    private final List<String> capabilities;

    private PdpMetadata(Builder builder) {
        this.policyDecisionPoint = Checks.requireSet(builder.policyDecisionPoint, "policyDecisionPoint");
        this.accessEvaluationEndpoint = Checks.requireSet(builder.accessEvaluationEndpoint, "accessEvaluationEndpoint");
        this.accessEvaluationsEndpoint = builder.accessEvaluationsEndpoint;
        this.searchSubjectEndpoint = builder.searchSubjectEndpoint;
        this.searchResourceEndpoint = builder.searchResourceEndpoint;
        this.searchActionEndpoint = builder.searchActionEndpoint;
        this.capabilities = List.copyOf(builder.capabilities);
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the identifier of the PDP ({@code policy_decision_point}).
     *
     * @return the identifier, a URL
     */
    public String getPolicyDecisionPoint() {
        return policyDecisionPoint;
    }

    /**
     * Returns the URL of the Access Evaluation API ({@code access_evaluation_endpoint}).
     *
     * @return the URL
     */
    public URI getAccessEvaluationEndpoint() {
        return accessEvaluationEndpoint;
    }

    /**
     * Returns the URL of the Access Evaluations API ({@code access_evaluations_endpoint}).
     *
     * @return the URL, or empty if the PDP does not support the API
     */
    public Optional<URI> getAccessEvaluationsEndpoint() {
        return Optional.ofNullable(accessEvaluationsEndpoint);
    }

    /**
     * Returns the URL of the Subject Search API ({@code search_subject_endpoint}).
     *
     * @return the URL, or empty if the PDP does not support the API
     */
    public Optional<URI> getSearchSubjectEndpoint() {
        return Optional.ofNullable(searchSubjectEndpoint);
    }

    /**
     * Returns the URL of the Resource Search API ({@code search_resource_endpoint}).
     *
     * @return the URL, or empty if the PDP does not support the API
     */
    public Optional<URI> getSearchResourceEndpoint() {
        return Optional.ofNullable(searchResourceEndpoint);
    }

    /**
     * Returns the URL of the Action Search API ({@code search_action_endpoint}).
     *
     * @return the URL, or empty if the PDP does not support the API
     */
    public Optional<URI> getSearchActionEndpoint() {
        return Optional.ofNullable(searchActionEndpoint);
    }

    /**
     * Returns the capabilities of the PDP ({@code capabilities}), registered URNs.
     *
     * @return an unmodifiable list, empty if the PDP lists none
     */
    public List<String> getCapabilities() {
        return capabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PdpMetadata other)) {
            return false;
        }
        return policyDecisionPoint.equals(other.policyDecisionPoint)
                && accessEvaluationEndpoint.equals(other.accessEvaluationEndpoint)
                && Objects.equals(accessEvaluationsEndpoint, other.accessEvaluationsEndpoint)
                && Objects.equals(searchSubjectEndpoint, other.searchSubjectEndpoint)
                && Objects.equals(searchResourceEndpoint, other.searchResourceEndpoint)
                && Objects.equals(searchActionEndpoint, other.searchActionEndpoint)
                && capabilities.equals(other.capabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyDecisionPoint, accessEvaluationEndpoint, accessEvaluationsEndpoint,
                searchSubjectEndpoint, searchResourceEndpoint, searchActionEndpoint, capabilities);
    }

    /**
     * Returns a description for logging. Metadata holds no personal data, so nothing is masked, but
     * control characters in the strings the PDP chose are escaped so that they cannot forge log
     * lines. URLs cannot contain them.
     */
    @Override
    public String toString() {
        return "PdpMetadata{policyDecisionPoint=" + LogText.of(policyDecisionPoint)
                + ", accessEvaluationEndpoint=" + accessEvaluationEndpoint
                + ", accessEvaluationsEndpoint=" + accessEvaluationsEndpoint
                + ", searchSubjectEndpoint=" + searchSubjectEndpoint
                + ", searchResourceEndpoint=" + searchResourceEndpoint
                + ", searchActionEndpoint=" + searchActionEndpoint
                + ", capabilities=" + capabilities.stream().map(LogText::of).toList() + "}";
    }

    /**
     * Builder for {@link PdpMetadata}. Not thread-safe.
     */
    public static final class Builder {

        private String policyDecisionPoint;
        private URI accessEvaluationEndpoint;
        private URI accessEvaluationsEndpoint;
        private URI searchSubjectEndpoint;
        private URI searchResourceEndpoint;
        private URI searchActionEndpoint;
        private final List<String> capabilities = new ArrayList<>();

        private Builder() {
        }

        /**
         * Sets the identifier of the PDP. Required.
         *
         * @param policyDecisionPoint the identifier, a URL
         * @return this builder
         */
        public Builder policyDecisionPoint(String policyDecisionPoint) {
            this.policyDecisionPoint = policyDecisionPoint;
            return this;
        }

        /**
         * Sets the URL of the Access Evaluation API. Required.
         *
         * @param endpoint the URL
         * @return this builder
         */
        public Builder accessEvaluationEndpoint(URI endpoint) {
            this.accessEvaluationEndpoint = endpoint;
            return this;
        }

        /**
         * Sets the URL of the Access Evaluations API.
         *
         * @param endpoint the URL, or {@code null} if the PDP does not support the API
         * @return this builder
         */
        public Builder accessEvaluationsEndpoint(URI endpoint) {
            this.accessEvaluationsEndpoint = endpoint;
            return this;
        }

        /**
         * Sets the URL of the Subject Search API.
         *
         * @param endpoint the URL, or {@code null} if the PDP does not support the API
         * @return this builder
         */
        public Builder searchSubjectEndpoint(URI endpoint) {
            this.searchSubjectEndpoint = endpoint;
            return this;
        }

        /**
         * Sets the URL of the Resource Search API.
         *
         * @param endpoint the URL, or {@code null} if the PDP does not support the API
         * @return this builder
         */
        public Builder searchResourceEndpoint(URI endpoint) {
            this.searchResourceEndpoint = endpoint;
            return this;
        }

        /**
         * Sets the URL of the Action Search API.
         *
         * @param endpoint the URL, or {@code null} if the PDP does not support the API
         * @return this builder
         */
        public Builder searchActionEndpoint(URI endpoint) {
            this.searchActionEndpoint = endpoint;
            return this;
        }

        /**
         * Adds a capability.
         *
         * @param capability a registered URN
         * @return this builder
         */
        public Builder capability(String capability) {
            capabilities.add(Objects.requireNonNull(capability, "capability must not be null"));
            return this;
        }

        /**
         * Builds the metadata.
         *
         * @return the metadata
         * @throws IllegalStateException if the identifier or the Access Evaluation endpoint is not set
         */
        public PdpMetadata build() {
            return new PdpMetadata(this);
        }
    }
}
