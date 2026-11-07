# Evaluations

## One decision

`evaluate` sends an Access Evaluation request and returns the PDP's decision:

```java
EvaluationResponse response = client.evaluate(EvaluationRequest.of(
        Subject.of("user", "alice"),
        Action.of("read"),
        Resource.of("document", "123")));
```

The subject and resource have a type and an id, and the action has a name. Anything else goes into
their properties or into the request context, as the specification requires. A deny is a regular
response, not an exception; see [Getting started](getting-started.md#denies-and-errors).

## Several decisions in one call

`evaluations` sends an Access Evaluations (batch) request. The top-level subject, action, resource
and context are defaults; an item replaces any of them as a whole. The request is sent as built, and
the PDP applies the defaults.

```java
EvaluationsResponse response = client.evaluations(EvaluationsRequest.builder()
        .subject(Subject.of("user", "alice"))
        .action(Action.of("read"))
        .evaluation(EvaluationItem.builder().resource(Resource.of("document", "a")).build())
        .evaluation(EvaluationItem.builder().action(Action.of("update")).resource(Resource.of("document", "b")).build())
        .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY)
        .build());

if (response.allPermitted()) {
    // every item allowed
}
```

The decisions come back in request order. With `DENY_ON_FIRST_DENY` or `PERMIT_ON_FIRST_PERMIT`, the
PDP stops at the deciding item, and the items after it count as denies. `isPermitted(i)`,
`allPermitted()` and `anyPermitted()` never treat a missing decision as a permit. If the answer has
more decisions than the request had items, or fewer under `EXECUTE_ALL` (the default), the call fails
with a `PdpProtocolException`.

The endpoint comes from `access_evaluations_endpoint` in the metadata. If the PDP publishes metadata
without it, the PDP does not offer this API: the call fails without contacting it, and the
application can use `evaluate` for each item instead. Without metadata, the default path
`/access/v1/evaluations` is used, and `evaluationsEndpoint(URI)` sets the endpoint explicitly.
