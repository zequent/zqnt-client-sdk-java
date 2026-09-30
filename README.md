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
│ Service (Port 8002) │  │ Service (Port 8004) │  │ (Port 8003)        │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

## 🚀 For Customers: How to Use

### 1. Add Dependency to Your Project

```xml
<dependency>
    <groupId>com.zequent.framework.client.sdk</groupId>
    <artifactId>java-client-sdk</artifactId>
    <version>1.0-SNAPSHOT</version>
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

    public void handleTelemetry() {
        zequent.liveData().streamTelemetryData();
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

### Dynamic payload commands

Dynamic payload commands run through `remoteControl()`. Discover the current capabilities first:
their target reference is the authoritative routing value for the command.

The client flow is:

1. Call `remoteControl().getCapabilities(dockSn)`.
2. Select the advertised logical command.
3. Build the request with `CustomCommandRequest.forCapability(...)` so its `targetRef` is retained.
4. Send the command and check `CustomCommandResponse.success`, `error`, and `result`.

The `vendorMethod` from the discovered definition is intentionally not sent by the client. The edge
adapter resolves the stable logical command to the vendor-specific implementation.

```java
String dockSn = "YOUR_DOCK_SN";

return zequent.remoteControl().getCapabilities(dockSn).thenCompose(snapshot -> {
    var capability = snapshot.getCapabilities().stream()
            .filter(value -> "parachute.led.set".equals(value.getCommandId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Parachute capability is unavailable"));

    // value: 1 = on, 0 = off. Widget index 1 is added by the adapter.
    return zequent.remoteControl().sendCustomCommand(
            CustomCommandRequest.forCapability(dockSn, capability, Map.of("value", 1)));
});
```

If `parachute.led.set` is absent, the payload was not recognized as `PARACHUTE`; its current PSDK
name must contain `flyfire` or `parachute`.

The complete executable example is
[`PayloadCustomCommandIntegrationTest`](src/test/java/com/zqnt/sdk/client/PayloadCustomCommandIntegrationTest.java).
Its first test only discovers and constructs the request. The second test changes hardware state and
therefore runs only with `-Dpayload.command.execution.enabled=true`.

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
public class LiveDataService {

    private final ZequentClient zequentClient;  // ← Injected by Spring

    public void handleTelemetry() {
        zequentClient.liveData().streamTelemetryData();
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
- Service interfaces (RemoteControl, MissionAutonomy, LiveData, Connector)
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
✅ **Multi-Service Support** - Remote Control, Mission Autonomy, Live Data, Connector
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

Copyright © 2025 Zequent Framework
