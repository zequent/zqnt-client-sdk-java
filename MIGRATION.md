# Migrating from 2.x typed calls to `executeCommand`

Zequent 3.0 commands every asset the same way: ask the asset what it can do, then run a command by
its dotted id with a JSON object of params.

```java
CapabilitySet capabilities = zequent.commands().listCapabilities("DOCK-1").join();
CommandResult result = zequent.commands()
        .executeCommand("DOCK-1", "navigation.go_to", Map.of("latitude", 52.52, "longitude", 13.405, "altitude", 60))
        .join();
```

The 2.x typed calls are removed in 3.0: `zequent.remoteControl()` and `zequent.liveData()` no longer
exist, together with their request/response classes (`TakeoffRequest`, `GoToRequest`,
`DockOperationRequest`, `RemoteControlResponse`, `CapabilitySnapshot`, `CustomCommandRequest`,
`StreamTelemetryRequest`, ...). Use `commands()` for every command and `telemetry()` for live data.
To talk to a 2.x platform, stay on a 2.x SDK.

## Typed call → command id + params

The capability's `input_schema` from `listCapabilities` is authoritative for an asset; the params
below are the ones the built-in commands of every Zequent adapter accept.

| 2.x typed call | Command id | Params |
|---|---|---|
| `takeoff(TakeoffRequest)` | `flight.takeoff` | `latitude`, `longitude`, `altitude` |
| `goTo(GoToRequest)` | `navigation.go_to` | `latitude`, `longitude`, `altitude` (metres above the **takeoff point**; left out = 40 m) |
| `goTo(request, true)` | `navigation.go_to` | as above, plus `CommandRequest.noFlyZoneOverride(true)` |
| `returnToHome(ReturnToHomeRequest)` | `flight.return_to_home` | `altitude` (optional) |
| `lookAt(LookAtRequest)` | `gimbal.look_at` | `latitude`, `longitude`, `altitude`, `locked`, `payloadIndex` |
| `enterManualControl(...)` | `flight.manual.enter` | none |
| `exitManualControl(...)` | `flight.manual.exit` | none |
| `openCover(...)` | `dock.open_cover` | none |
| `closeCover(...)` | `dock.close_cover` | `force` (boolean, optional) |
| `startCharging(...)` | `dock.start_charging` | none |
| `stopCharging(...)` | `dock.stop_charging` | none |
| `rebootAsset(...)` | `asset.reboot` | none |
| `bootSubAsset(...)` | `asset.boot_sub_asset` | `enabled` (boolean: `true` boots, `false` shuts down) |
| `debugMode(...)` | `asset.remote_debug` | `enabled` (boolean) |
| `changeAcMode(...)` | `asset.change_ac_mode` | `mode` (one of the values in the capability's schema) |
| `takePhoto(...)` | `camera.take_photo` | none |
| `liveStreamSplitScreen(...)` | `stream.split_screen` | `enabled` (boolean) |
| `getCapabilities(sn)` | `listCapabilities(sn)` | returns the v3 `CapabilitySet` |
| `sendCustomCommand(CustomCommandRequest.forCapability(sn, capability, params))` | the capability's `command_id` | the same params; the capability's target goes in `CommandRequest.target(...)` |

The camera and live-stream calls of `liveData()` are commands too:

| 2.x typed call | Command id | Params |
|---|---|---|
| `liveData().changeCameraLens(ChangeLensRequest)` | `camera.change_lens` | `lens`, `videoId` |
| `liveData().changeCameraZoom(ChangeZoomRequest)` | `camera.change_zoom` | `lens`, `payloadIndex`, `zoom` |
| `liveData().startLiveStream(LiveDataStartLiveStreamRequest)` | `stream.start` | as in the capability's `input_schema` |
| `liveData().stopLiveStream(LiveDataStopLiveStreamRequest)` | `stream.stop` | as in the capability's `input_schema` |

**Leave a param out when you have no value.** The platform reads a missing coordinate as "not
given"; sending `0` means latitude 0 / longitude 0 / ground level. A `null` value in the params map
is left out for you.

## Responses

| 2.x | 3.0 |
|---|---|
| `RemoteControlResponse.success` | `CommandResult.getState()`: `ACCEPTED`/`RUNNING` (still underway), `SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT` |
| `error.errorCode` / `errorMessage` | `CommandResult.getError()`: `category`, stable `code` (e.g. `flight.not_airborne`), `message`, `retryable` |
| a refused call (`StatusRuntimeException`) | `CommandException` with `getCategory()`, `getCode()`, `getStatus()` |
| a command refused before it started | `CommandException` with the rejected `getResult()` (e.g. `INVALID_ARGUMENT` / `command.invalid_params`) |
| waiting for the outcome | `executeAndWait(assetSn, commandId, params, wait)`: the `SUCCEEDED` result, or `CommandException` with the final `getResult()` |
| `progress` | `watchCommand(commandExecutionId, event -> ...)`: `progress`, `remaining`, `message`, the final `result` |
| `tid` | `CommandResult.getCommandExecutionId()`: watch it, cancel it with `cancelCommand(id, reason)` |
| `result` of a custom command (a map) | `Structs.toMap(result.getResult())` |

## Options

```java
zequent.commands().executeCommand(CommandRequest.builder()
        .assetSn("DOCK-1")
        .commandId("flight.return_to_home")
        .param("altitude", 80)
        .timeout(Duration.ofMinutes(10))
        .reason("battery low")
        .idempotencyKey(myRequestId)
        .build());
```

`reason` is required for commands whose capability is `COMMAND_RISK_CRITICAL`. The same
`idempotencyKey` sent twice runs the command once.

## Manual control

| 2.x | 3.0 |
|---|---|
| `remoteControl().startManualControlInput(sn, assetId)` | `commands().streamManualControl(assetSn)` |
| `session.sendInput(ManualControlInput)` | `stick.send(roll, pitch, yaw, throttle)` or `stick.send(edge.v3.ManualControlInput)` (with `gimbalPitch`) |
| `session.complete()` (a `RemoteControlResponse`) | `stick.close()`, then `stick.done()`: the number of inputs passed on to the asset |
| a broken stream | `stick.done()` fails with a `CommandException`; `send` throws `IllegalStateException` |

Enter and leave manual control with the commands `flight.manual.enter` and `flight.manual.exit`.

## Live data

| 2.x | 3.0 |
|---|---|
| `liveData().streamTelemetryData(request, onData)` | `telemetry().watchTelemetry(assetSns, minInterval, onSample, onSourceStatus)` |
| `liveData().streamNotifications(request, onData, onError)` | `telemetry().watchAlerts(assetSns, minSeverity, onAlert)` |
| a returned `StreamHandle`, `stop()` | a `TelemetryWatch`: `close()`, `done()` |
| per-vendor telemetry types | `TelemetrySample`: position, relative altitude, heading, speeds, battery; the rest in `details` under the capabilities' `telemetry_fields` |
| reconnects on its own | fails `done()` on a refusal, a dropped stream or missing heartbeats; watch again |
| `streamInactivityTimeoutSeconds`, `liveDataSchedulerThreads` | removed; `telemetryHeartbeatTimeoutSeconds` bounds every watch |

`connector().assetMonitoring(...)` returns its `StreamHandle` from `com.zqnt.sdk.client.connector.domains`
now (it was in `livedata.domains`).
