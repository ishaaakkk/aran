---
title: 'Extend Aran: writing a new operator'
sidebar_label: 'Write a new operator'
sidebar_position: 10
description: 'Create an operator module, implement the Operator contract, and register it with the application.'
# Diátaxis mode: how-to
---

An operator is a platform capability with a stable contract. Adding one
takes a Gradle module, a class, and two lines of wiring. There is no plugin
descriptor, no registry to edit, and no classloader.

## 1. Create the module

```
aran-operators/my-capability/
└── build.gradle
```

```groovy
dependencies {
    implementation project(':aran-core')
    implementation 'org.jspecify:jspecify'
    implementation 'org.springframework:spring-context'
    implementation 'org.slf4j:slf4j-api'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
    testImplementation 'org.assertj:assertj-core:3.27.3'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

Depend on `aran-core` and nothing else of Aran's. If an operator needs
another operator's output, it should take that output as a parameter or
read it from a port. The modules cannot see each other — this is
deliberate.

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

`@Component` is the whole registration. Spring's component scanning finds
it.

### The request

```java
record OperatorRequest(
    String tenantId,          // never blank; resolved from the caller's token
    @Nullable UUID userId,    // null only for system-internal invocations
    String operation,
    Map<String, String> parameters)
```

**The tenant arrives explicitly.** Do not read it from ambient state.
Operators run on job threads as well as request threads, so the request is
the contract.

**Parameters are always strings.** Numbers, booleans, and JSON all arrive as
strings. You parse them yourself.

### The result

```java
OperatorResult.success(Map.of("key", "value"));
OperatorResult.failure("ERROR_CODE", "Human-readable explanation");
```

A failure returns an error *code* as well as a message. The API surfaces
this code, and a job row records it, so make the code stable and
machine-readable.

Payloads are `Map<String, String>`. Put anything structured in as
serialised JSON on one key. This is what the query engine does with its
result sets.

## 3. Declare a manifest

This step is optional, but do it anyway. The manifest is how callers
discover your operation, and how the controller validates parameters
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
to prefer `/submit` over `/invoke`. Set it honestly. It is the only signal a
client has that a synchronous call will hang.

## 4. Report progress, if you have any

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

The default implementation ignores the reporter and delegates to the
single-argument `execute`. So an operator with nothing true to say about
its progress needs no change.

**Stages should be real phase boundaries**, not a decorative countdown.
Only call `percent` when the number means something. An honest spinner
beats a fabricated bar, and the UI already renders one.

## 5. Wire it into the application

In `aran-application/build.gradle`:

```groovy
runtimeOnly project(':aran-operators:my-capability')
```

Use `runtimeOnly`, not `implementation`. The application compiles against
the core's ports only. It must not reference your operator by type — that
boundary keeps the hexagon intact.

## 6. Test it

Operator tests are plain JUnit against the class. You need no Spring
context:

1. Build an `OperatorRequest`.
2. Call `execute`.
3. Assert on the `OperatorResult`.

Keep any security-relevant guard package-private rather than private, so a
test can drive its inputs directly. The query engine's read-only SQL guard
is written this way for exactly this reason. A boundary reachable only
through a full invocation with a live catalog is a boundary that never gets
tested.

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
- [ ] `@Component` on the class
- [ ] `runtimeOnly` in `aran-application/build.gradle`
- [ ] Unknown operations return `UNSUPPORTED_OPERATION`, not an exception
- [ ] Manifest declares every operation and parameter
- [ ] `longRunning` set honestly
- [ ] Tenant read from the request, never from ambient state
- [ ] Error codes stable and machine-readable

## Related

- [Module layout](../contributing/module-layout.md) — the dependency rules.
- [Architecture](../overview/architecture.mdx) — why the boundary is shaped this way.
