---
title: 'Extend Aran: writing a new operator'
sidebar_label: 'Write a new operator'
sidebar_position: 10
description: 'Create an operator module, implement the Operator contract, and register it with the application.'
# Diátaxis mode: how-to
---

An operator is a platform capability with a stable contract: bootstrapping,
profiling and the query engine are all operators. Use this page when you add
a capability of your own. It takes a Gradle module, a class, and two lines of
wiring. There is no plugin descriptor, registry to edit or classloader
involved.

## 1. Create the module

```
aran-operators/my-capability/
└── build.gradle
```

```groovy
plugins {
    id 'java-library'
}

dependencies {
    implementation project(':aran-core')
    implementation 'org.jspecify:jspecify'
    implementation 'org.springframework:spring-context'
}
```

The root `build.gradle` gives every module the rest: the Java toolchain, the
Spring Boot dependency versions, `slf4j-api`, `spring-boot-starter-test` for
JUnit 5 and AssertJ, and `useJUnitPlatform()`. Do not pin versions here.

Depend on `aran-core` and nothing else of Aran's. The operator modules cannot
see each other, by design, so an operator that needs
another operator's output should take that output as a parameter or
read it from a port.

Add the module to `settings.gradle`:

```groovy
include 'aran-operators:my-capability'
```

## 2. Implement `Operator`

```java
@Component
@NullMarked
public final class MyCapabilityOperator implements Operator {

    @Override
    public String getOperatorId() {
        return "my-capability";
    }

    @Override
    public String getDisplayName() {
        return "My Capability";
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        return switch (request.operation()) {
            case "ping" -> OperatorResult.success(Map.of("status", "ok"));
            case "do-the-thing" -> doTheThing(request);
            default -> OperatorResult.failure(
                    "UNSUPPORTED_OPERATION",
                    "My Capability does not support operation '" + request.operation() + "'");
        };
    }
}
```

`@Component` is the whole registration, because Spring's component scanning
finds the class. The application scans only `edu.upc.dtak.aran.app` and
`edu.upc.dtak.aran.operators`, so put the class in a package under
`edu.upc.dtak.aran.operators` (for example
`edu.upc.dtak.aran.operators.mycapability`). Anywhere else it compiles and is
never registered.

### The request

```java
record OperatorRequest(
    String tenantId,          // never blank; resolved from the caller's token
    @Nullable UUID userId,    // null only for system-internal invocations
    String operation,
    Map<String, String> parameters)
```

The tenant arrives explicitly in the request. Do not read it from ambient state:
operators run on job threads as well as request threads, so the request is
the contract.

Parameters are always strings. Numbers, booleans and JSON all arrive as
strings, and you parse them yourself.

### The result

```java
OperatorResult.success(Map.of("key", "value"));
OperatorResult.failure("ERROR_CODE", "Human-readable explanation");
```

A failure returns an error *code* as well as a message. The API surfaces
this code and a job row records it, so keep the code stable and
machine-readable.

Payloads are `Map<String, String>`. Put anything structured in as
serialised JSON on one key, as the query engine does with its
result sets.

## 3. Declare a manifest

This step is optional, but callers discover your operation through the
manifest, and the controller uses it to validate parameters
*before* your code runs.

```java
@Override
public OperatorManifest manifest() {
    return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
        new OperationSpec("ping", "Health check", false, List.of()),
        new OperationSpec("do-the-thing", "Does the thing", true, List.of(
            ParameterSpec.required("datasetId", "UUID of the dataset"),
            ParameterSpec.optional("threshold", "Score threshold", "0.5")
        ))
    ));
}
```

The `true` on the second `OperationSpec` is `longRunning`. It tells callers
to prefer `/submit` over `/invoke`. Set it accurately, because it is the only
signal a client has that a synchronous call will hang.

Without a manifest override, the operator lists no operations and the
controller checks no parameters for it.

## 4. Report progress

```java
@Override
public OperatorResult execute(OperatorRequest request, ProgressReporter progress) {
    progress.stage("downloading snapshot");
    // …
    progress.stage("building lattice");
    progress.percent(40);
    // …
    return OperatorResult.success(Map.of("status", "ok"));
}
```

This step is optional. The default implementation ignores the reporter and
delegates to the single-argument `execute`, so an operator with nothing
accurate to report about its progress needs no change. `ProgressReporter` has
two methods: `stage(String)` names the phase just entered, and `percent(int)`
takes 0 to 100. A job's event stream carries both.

Make stages real phase boundaries, not a decorative countdown, and call
`percent` only when the number means something. A fabricated bar is worse than the plain
spinner the UI already renders.

## 5. Wire it into the application

In `aran-application/build.gradle`:

```groovy
runtimeOnly project(':aran-operators:my-capability')
```

Use `runtimeOnly`, not `implementation`. The application compiles against
the core's ports only and must not reference your operator by type, which
keeps the hexagonal boundary intact.

## 6. Test it

Operator tests are plain JUnit against the class, with no Spring
context:

1. Build an `OperatorRequest`.
2. Call `execute`.
3. Assert on the `OperatorResult`.

Keep any security-relevant guard package-private rather than private, so a
test can drive its inputs directly. The query engine's read-only SQL guard
is written this way. A boundary reachable only through a full invocation
with a live catalog tends never to be tested.

## Calling it

```http
GET  /api/operators                         # your manifest appears here
POST /api/operators/my-capability/invoke
POST /api/operators/my-capability/submit
```

Nothing else needs to change. See
[Invoke a capability operator](./invoke-a-capability-operator.md).

## Checklist

- [ ] Module depends on `aran-core` only
- [ ] Listed in `settings.gradle`
- [ ] Class in a package under `edu.upc.dtak.aran.operators`
- [ ] `@Component` on the class
- [ ] `runtimeOnly` in `aran-application/build.gradle`
- [ ] Unknown operations return `UNSUPPORTED_OPERATION`, not an exception
- [ ] Manifest declares every operation and parameter
- [ ] `longRunning` set accurately
- [ ] Tenant read from the request, never from ambient state
- [ ] Error codes stable and machine-readable

## Related

- [Module layout](../contributing/module-layout.md): the dependency rules.
- [Architecture](../overview/architecture.mdx): why the boundary is shaped this way.
