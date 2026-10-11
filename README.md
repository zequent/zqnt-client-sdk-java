# Zequent Client SDK

> **Java Client Library** for interacting with Zequent Framework Services

## ⚠️ Important: This is a Library, Not a Standalone Application!

The **Zequent Client SDK** is a **Java library/dependency** that customers add to their applications (Spring Boot, Quarkus, etc.). It is **NOT** a standalone service or application that runs by itself.

## 🏗️ Architecture

```
┌─────────────────────────────────────┐
│   CUSTOMER'S APPLICATION            │
│   - Their REST API / Service        │
│   - Their Business Logic            │
│   - @Inject ZequentClient ←──────┐  │
└─────────────────────────────────────┘
                                     │
                            ┌────────┴────────┐
                            │  Zequent SDK    │
                            │  (This Library) │
                            └────────┬────────┘
                                     │
            ┌────────────────────────┼────────────────────────┐
            │                        │                        │
            ▼                        ▼                        ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│ Remote Control      │  │ Mission Autonomy    │  │ Live Data Service   │
│ commands() (8002)   │  │ (Port 8004)         │  │ telemetry() (8003)  │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

## 🚀 For Customers: How to Use

### 1. Add Dependency to Your Project

```xml
<dependency>
    <groupId>com.zqnt.sdk</groupId>
    <artifactId>client-java-sdk</artifactId>
    <version>3.0.0</version>
</dependency>
```

### 2. Get a client credential

The platform refuses every call that carries no credential. An organization administrator issues
one in the console under **Deploy → Access & Integrations → Credentials** (kind **client**). It is
shown once, belongs to that one organization, and reaches only that organization's assets,
Applications and runs — never users, organizations or other administration.

Hand it to the SDK in one of three ways (first one set wins):

```java
ZequentClient client = ZequentClient.builder().clientToken(token)   // 1. explicitly
        .connector().host("core.example.com").port(8010).done()
        .build();
```

```properties
zequent.client-token=${ZQNT_CLIENT_TOKEN}   # 2. Quarkus configuration
```

```bash
export ZQNT_CLIENT_TOKEN=eyJhbGciOiJFZERTQSIs...   # 3. the environment
```

It is sent as `authorization: Bearer <token>` on every call. A refusal surfaces as a
`StatusRuntimeException` whose message says what to do: `UNAUTHENTICATED` — no credential, or an
expired/revoked one; `PERMISSION_DENIED` — the call is outside what a client credential may do (an
asset of another organization, or an administrative RPC). Neither is retried.

#### Local development and deployment: one set of environment variables

`ZequentClient.fromEnvironment()` (or `builder().fromEnvironment()` to add interceptors or pin one
service) reads the variables every Zequent client SDK — Java, Python, Go — reads; the Quarkus
integration maps the same names.

| Variable | Local default (nothing set) |
|---|---|
| `CONNECTOR_SERVICE_HOST` / `_PORT` / `_USE_PLAINTEXT` | `localhost` / `8010` / `true` |
| `REMOTE_CONTROL_SERVICE_HOST` / `_PORT` / `_USE_PLAINTEXT` | `localhost` / `8002` / `true` |
| `LIVE_DATA_SERVICE_HOST` / `_PORT` / `_USE_PLAINTEXT` | `localhost` / `8003` / `true` |
| `MISSION_AUTONOMY_SERVICE_HOST` / `_PORT` / `_USE_PLAINTEXT` | `localhost` / `8004` / `true` |
| `ZQNT_CLIENT_TOKEN` | none — issue one in your local console too |

With nothing set, a developer reaches the local stack (`quarkus:dev` or `docker-compose.local.yml`).
A deployment sets the hosts, `_USE_PLAINTEXT=false` for TLS against the system trust store whenever
the traffic leaves a private network, and `ZQNT_CLIENT_TOKEN` from its secret store — never from a
committed file. There is deliberately no built-in development credential: the local platform
refuses anonymous calls just like a deployment does.

#### A credential that is not one fixed token

A service that forwards its own caller's token, or rotates a short-lived one, registers a gRPC
`ClientInterceptor` instead. It is put on every channel the SDK creates (all four services, unary
and streaming calls) and runs before the SDK's own credential interceptor: an `authorization`
header it sets wins, and the fixed client token is then not sent.

```java
ZequentClient client = ZequentClient.builder().interceptor(myAuthInterceptor) /* ... */ .build();
```

In Quarkus, mark a `ClientInterceptor` bean with `@ZequentClientInterceptor` and the injected
`ZequentClient` picks it up:

```java
@Produces @ZequentClientInterceptor
ClientInterceptor zequentCredentials(MyAuthInterceptor interceptor) { return interceptor; }
```

### 3. Setup for Your Framework

#### 🔹 Quarkus (Automatic via CDI)

**application.properties:**
```properties
zequent.remote-control-service.host=localhost
zequent.remote-control-service.port=8002
zequent.live-data-service.host=localhost
zequent.live-data-service.port=8003
zequent.connector-service.host=localhost
zequent.connector-service.port=8010
```

**Usage:**
```java
@ApplicationScoped
public class DroneService {
    @Inject
    ZequentClient zequent;  // ← Automatically configured!

    public TelemetryWatch watchDock(Consumer<TelemetrySample> onSample) {
        return zequent.telemetry().watchTelemetry(List.of("DOCK-1"), onSample);
    }

    public CompletableFuture<ConnectorResponse> findAsset(String sn) {
        ConnectorRequestContext context = ConnectorRequestContext.builder()
                .sn(sn)
                .tid(UUID.randomUUID().toString())
                .build();
        return zequent.connector().getAssetBySn(
                GetAssetBySnRequest.builder().context(context).build()
        );
    }
}
```

### Commands

Every asset is commanded the same way: list what it can do, then run a command by
its dotted id with a JSON object of params. There are no typed methods per command.

```java
Commands commands = zequent.commands();

CapabilitySet capabilities = commands.listCapabilities("DOCK-1").join();
capabilities.getCapabilitiesList().forEach(c ->
        System.out.println(c.getCommandId() + " " + c.getSafety().getRisk() + " " + c.getInputSchema()));

CommandResult takeoff = commands.executeAndWait("DOCK-1", "flight.takeoff",
        Map.of("latitude", 47.7752, "longitude", 9.2658, "altitude", 40), Duration.ofMinutes(5)).join();

CommandResult goTo = commands.executeCommand("DOCK-1", "navigation.go_to",
        Map.of("latitude", 47.7760, "longitude", 9.2671, "altitude", 60)).join();
try (CommandWatch watch = commands.watchCommand(goTo.getCommandExecutionId(),
        event -> System.out.println(event.getState() + " " + event.getProgress()))) {
    watch.done().get(5, TimeUnit.MINUTES);
}
```

- `listCapabilities(assetSn)`: the asset's `CapabilitySet`: command ids, input/output JSON
  schemas, risk, the errors and events each command declares.
- `executeCommand(assetSn, commandId, params)`: returns the `CommandResult`: `ACCEPTED`/`RUNNING`
  with a `commandExecutionId` while it is underway, or the final state. `executeCommand(CommandRequest)`
  adds a target, a timeout, a reason (required for CRITICAL commands), an idempotency key and the
  no-fly zone override.
- `executeAndWait(assetSn, commandId, params, wait)` / `executeAndWait(CommandRequest, wait)`: runs
  the command and completes with its `SUCCEEDED` result. It watches the asset before it sends the
  command, so a run that finishes right away is not missed, and ignores the events of other runs.
  `wait` bounds the wait (`null` = no limit); when it elapses the future fails with category
  `TIMEOUT` and the command keeps running (`cancelCommand` stops it). Completing or cancelling the
  future closes the watch.
- `watchCommand(commandExecutionId, onEvent)` / `watchAsset(assetSn, onEvent)`: command events
  from now on; `done()` completes when the run ends or the watch is closed. A watch opened after
  `executeCommand` returns can miss the end of a fast run; use `executeAndWait` to wait for it.
- `cancelCommand(commandExecutionId, reason)`.
- Errors: a refused call or a command rejected before it started fails the future with a
  `CommandException` (`getCategory()`, `getCode()` such as `command.invalid_params`, `getStatus()`).
  From `executeCommand`, a command that started and failed is a `CommandResult` with state `FAILED`
  and its error; `executeAndWait` fails with a `CommandException` for a run that ends `FAILED`,
  `CANCELLED` or `TIMED_OUT`, with that final `getResult()`.
- `navigation.go_to` altitude is metres above the **takeoff point**. Leave a param out when you have
  no value; never send `0` for "not given". `null` map values are left out.
- `Structs.toMap(result.getResult())` turns a result or event payload into a plain map.

`commands()` uses the remote-control service connection, credential and interceptors already
configured for the client; nothing else to set up.

### Manual control

Stick input goes over one stream per asset. Put the asset in manual control first, then send
inputs (roll, pitch, yaw, throttle from -1 to 1) at your control rate:

```java
commands.executeAndWait("DRONE-1", "flight.manual.enter", Map.of(), Duration.ofSeconds(30)).join();
try (ManualControlStream stick = commands.streamManualControl("DRONE-1")) {
    stick.send(0f, 0.2f, 0f, 0.5f);
    stick.send(ManualControlInput.newBuilder().setThrottle(0.5f).setGimbalPitch(-30f).build());
}
```

The first input names the asset; one stream controls one asset. `close()` ends the session and
`done()` completes with the number of inputs the platform passed on to the asset. `done()` fails
with a `CommandException` when the platform refuses the session (no adapter reachable, an asset of
another organization) or the adapter drops it; `send` then throws `IllegalStateException`. Leave
manual control with `flight.manual.exit`.

### Live data

```java
try (TelemetryWatch watch = zequent.telemetry().watchTelemetry(List.of("DOCK-1"), Duration.ofSeconds(1),
        sample -> System.out.println(sample.getPosition() + " " + sample.getBatteryPercent()),
        status -> System.out.println(status.getAsset().getSn() + " " + status.getState()))) {
    watch.done().get(10, TimeUnit.MINUTES);
}
```

`watchTelemetry(assetSns, minInterval, onSample, onSourceStatus)` streams `TelemetrySample`s from
the live-data service: the shared fields (position, relative altitude, heading, speeds, battery) and
the device-specific `details` under the keys of the capabilities' `telemetry_fields`. No asset
watches every asset of the caller's organization; a dock's serial includes its drone. `minInterval`
keeps at most one sample per asset per interval (`null` = every sample). `onSourceStatus` hears each
watched asset's state first, then each change to online, stale or no data. The platform sends
heartbeats on a quiet stream; when neither a sample nor a heartbeat arrives within
`telemetryHeartbeatTimeoutSeconds` the watch fails with a retryable `SERVICE` `CommandException`.
A watch does not reconnect: watch again.

```java
zequent.telemetry().watchDetections(List.of("DRONE-1"), batch -> System.out.println(batch.getDetectionsCount()));
zequent.telemetry().watchAlerts(null, AlertSeverity.ALERT_SEVERITY_WARNING,
        alert -> System.out.println(alert.getAsset().getSn() + " " + alert.getCode() + " " + alert.getMessage()));
```

`watchDetections(assetSns, onBatch)` streams the `DetectionBatch`es of the given assets (AI
detections with the stream they came from); `watchAlerts(assetSns, minSeverity, onAlert)` streams
`Alert`s (a stable `code` such as `dock.rain`, a severity, a message and `details`) at
`minSeverity` or above (`null` = every severity). Both send heartbeats and fail like
`watchTelemetry`.

**Upgrading from 2.x:** the typed calls of 2.x (`remoteControl()` and `liveData()`) are gone in
3.0. [MIGRATION.md](MIGRATION.md) maps every typed call to its command id and params.

**That's it!** The SDK auto-configures via `ZequentClientProducer` (CDI).

---

#### 🔹 Spring Boot (Simple Bean Configuration)

**Create a Configuration class:**
```java
@Configuration
public class ZequentConfig {

    @Bean
    public ZequentClient zequentClient() {
        // Uses defaults: localhost:8002, 8004, 8003, 8010
        return ZequentClient.builder()
                .remoteControl().done()
                .missionAutonomy().done()
                .liveData().done()
                .connector().done()
                .build();
    }
}
```

**Usage:**
```java
@Service
@RequiredArgsConstructor
public class DockService {

    private final ZequentClient zequentClient;  // ← Injected by Spring

    public CompletableFuture<CommandResult> openCover(String dockSn) {
        return zequentClient.commands().executeAndWait(dockSn, "dock.open_cover", Map.of(), Duration.ofMinutes(2));
    }
}
```

**Optional - Override with Properties:**
```java
@Bean
public ZequentClient zequentClient(
        @Value("${zequent.remote-control.host:localhost}") String host,
        @Value("${zequent.remote-control.port:8002}") int port) {
    return ZequentClient.builder()
            .remoteControl().host(host).port(port).done()
            .missionAutonomy().done()
            .liveData().done()
            .connector().host("localhost").port(8010).done()
            .build();
}
```

**See detailed guide:** [`SPRING_BOOT_FINAL.md`](../../../docs/client-sdk/SPRING_BOOT_FINAL.md)

## 📚 Customer Documentation

### Quick Start Guides
- **[Spring Boot Integration](../../../docs/client-sdk/SPRING_BOOT_FINAL.md)** - 🔥 **START HERE for Spring Boot** - Simple, defaults-based setup
- **[Bean Configuration Guide](BEAN_CONFIGURATION.md)** - Advanced configuration options

### Reference
- **[Configuration Reference](CONFIGURATION.md)** - All available properties
- **[Quick Start Guide](../../../docs/QUICKSTART.md)** - 5-minute setup for any framework

## 🔧 For Developers: Building the SDK

### Build Library

```bash
mvn clean install
```

This creates a JAR that customers can add as a dependency.

### Run Tests

```bash
mvn test
```

### Deploy to Repository

```bash
mvn deploy
```

## 📦 What's Included

This SDK provides:
- `ZequentClient` - Main client interface
- `commands()`: any command by id (listCapabilities, executeCommand, executeAndWait, watch, cancel, streamManualControl)
- `telemetry()`: telemetry samples and source status, detections and alerts (watchTelemetry, watchDetections, watchAlerts)
- `missionAutonomy()`: Applications, Skills and their executions
- `connector()`: assets, payloads, policies and technical configuration
- Request/Response models
- Auto-configuration via CDI (Quarkus)
- gRPC channel management
- Resilience patterns (retry, circuit breaker)
- Load balancing & service discovery

## ☕ Java Version Compatibility

- **Compiled with:** Java 21
- **Compatible with:** Java 21, 22, 23, 24, 25+
- **Minimum required:** Java 21

The SDK is compiled with Java 21 for maximum customer compatibility. If your application uses Java 21 or higher, the SDK will work seamlessly.

## ✅ Features

✅ **Framework Agnostic** - Works with Spring Boot, Quarkus, Micronaut, etc.
✅ **Sensible Defaults** - Works out-of-the-box (localhost:8002/8004/8003/8010)
✅ **Property-Based Config** - Override via `application.properties` (optional)
✅ **Multi-Service Support** - Commands, Live Data, Mission Autonomy, Connector
✅ **Built-in Resilience** - Retry, Circuit Breaker, Timeouts
✅ **Load Balancing** - Round-robin, Least-requests
✅ **Service Discovery** - Stork support for Kubernetes
✅ **Simple Integration** - One `@Bean` method for Spring Boot, zero config for Quarkus

## 🚫 What This Is NOT

This SDK does **NOT**:
- ❌ Run as a standalone application
- ❌ Provide a REST API (customers build that)
- ❌ Include the actual Zequent services
- ❌ Need to be "started" or "deployed" separately

## 📝 License

Licensed under the [Apache License, Version 2.0](LICENSE); see [NOTICE](NOTICE).
