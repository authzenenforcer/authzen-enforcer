# Contributing to authzen-enforcer

Thank you for your interest in contributing. This document describes how to build the project
and the design rules every change must follow.

## Building

Requirements: JDK 11 or newer and Maven 3.9 or newer. The core module is compiled with
`--release 11`, so a newer JDK still produces Java 11 compatible bytecode and rejects newer APIs.

```bash
mvn verify                          # build and test all modules
mvn -pl authzen-enforcer-core verify  # core module only
```

CI runs the build on JDK 11, 17, 21 and 25.

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

### Java 11 baseline

The core must run on Java 11. Do not use APIs or language features introduced after Java 11
(records, sealed types, pattern matching, text blocks, `Stream#toList`, and so on).
Framework modules target Java 17.

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
