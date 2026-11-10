package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.model.Resource;

import java.util.Objects;

/**
 * A resource that a {@link ResourceInferrer} inferred, and whether it stands for a collection, which
 * selects the action for the HTTP method.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class InferredResource {

    private final Resource resource;
    private final boolean collection;

    private InferredResource(Resource resource, boolean collection) {
        this.resource = Objects.requireNonNull(resource, "resource must not be null");
        this.collection = collection;
    }

    /**
     * Returns a single resource, such as the document of {@code /documents/{id}}.
     *
     * @param resource the resource
     * @return the inferred resource
     */
    public static InferredResource single(Resource resource) {
        return new InferredResource(resource, false);
    }

    /**
     * Returns a collection, such as the documents of {@code /documents}.
     *
     * @param resource the resource that stands for the collection
     * @return the inferred resource
     */
    public static InferredResource collection(Resource resource) {
        return new InferredResource(resource, true);
    }

    /**
     * Returns the resource.
     *
     * @return the resource
     */
    public Resource getResource() {
        return resource;
    }

    /**
     * Returns whether the resource stands for a collection.
     *
     * @return {@code true} for a collection
     */
    public boolean isCollection() {
        return collection;
    }

    @Override
    public String toString() {
        return "InferredResource{" + resource + ", collection=" + collection + "}";
    }
}
