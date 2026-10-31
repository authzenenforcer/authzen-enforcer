# Contributing to authzen-enforcer

Thank you for your interest in contributing. This document describes how to build the project
and the design rules every change must follow.

## Building

Requirements: JDK 17 or newer and Maven 3.9 or newer. All modules are compiled with
`--release 17`, so a newer JDK still produces Java 17 compatible bytecode and rejects newer APIs.

```bash
mvn verify                          # build and test all modules
mvn -pl authzen-enforcer-core verify  # core module only
```

CI runs the build on JDK 17, 21 and 25.

## Design rules

### Zero runtime dependencies

`authzen-enforcer-core` depends on the JDK only. Anything outside the JDK (logging facades, JSON
libraries, HTTP clients, utility libraries) is not allowed, either directly or transitively.
Dependencies in `test` scope are fine.

The build enforces this rule with `maven-enforcer-plugin` and fails if the core declares a
`compile`, `runtime`, `provided` or `system` dependency. If you believe a new dependency is
unavoidable, open an issue to discuss it before sending a pull request.

Framework modules (Spring Boot, Quarkus) may depend on their framework, but never the other way
around: the core must not know about any framework.

### Java 17 baseline

All modules, including the core, must run on Java 17. Do not use APIs or language features
introduced after Java 17 (pattern matching for `switch`, record patterns, sequenced collections,
and so on).

Records in the public API need care: adding a component changes the canonical constructor and
breaks binary compatibility. Use builder-based `final` classes for public model types that may
gain fields as the specification evolves, and keep records for internal or genuinely fixed
value types.

### Fail closed

Any failure while talking to the PDP (network error, timeout, unexpected status, unparsable
response) must result in a deny, unless the user explicitly configures otherwise.

### Follow the specification

Request and response formats must match the
[AuthZEN Authorization API](https://openid.net/wg/authzen/) exactly. Data that the specification
does not define belongs in `properties` or `context`.

### Never log secrets

Access tokens, DPoP proofs and private keys must never be logged or included in exception messages.

## Pull requests

- Keep each pull request focused on a single change.
- Add or update tests for every behavior change.
- Make sure `mvn verify` passes locally.
- Document public API with Javadoc.
