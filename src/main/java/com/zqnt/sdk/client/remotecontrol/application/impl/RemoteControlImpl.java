package com.zqnt.sdk.client.remotecontrol.application.impl;


import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.grpc.GrpcResilience;
import com.zqnt.sdk.client.remotecontrol.application.ManualControlInputSession;
import com.zqnt.sdk.client.remotecontrol.application.RemoteControl;
import com.zqnt.sdk.client.remotecontrol.domains.*;
import com.zqnt.sdk.client.remotecontrol.domains.ManualControlRequest;
import com.zqnt.sdk.client.remotecontrol.domains.ReturnToHomeRequest;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.devicecontrol.proto.*;
import com.zqnt.utils.remotecontrol.proto.RemoteControlServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal implementation of RemoteControl using standard gRPC AsyncStub.
 * NOT exposed as a CDI bean - only accessible via ZequentClient.
 *
 * Performance optimizations:
 * - Uses AsyncStub (most performant) for ALL operations
 * - StreamObserver for callback-based non-blocking I/O
 * - CompletableFuture for framework-agnostic async operations
 * - Shared timeout scheduler for resource efficiency
 * - Circuit breaker and retry logic via GrpcResilience
 */
@Slf4j
public class RemoteControlImpl implements RemoteControl {

	private final RemoteControlServiceGrpc.RemoteControlServiceStub asyncStub;
	private final GrpcResilience resilience;
	private final GrpcClientConfig config;
	private final ScheduledExecutorService timeoutScheduler;
	private final CustomCommandMapper customCommandMapper = new CustomCommandMapper();
	private final CapabilityMapper capabilityMapper = new CapabilityMapper();

	/**
	 * Private constructor - use create() factory method.
	 */
	private RemoteControlImpl(GrpcClientConfig config, ManagedChannel channel) {
		this.config = config;
		this.resilience = new GrpcResilience(
				config.getMaxRetryAttempts(),
				config.getRetryDelayMillis(),
				config.getCircuitBreakerFailureThreshold(),
				config.getCircuitBreakerWaitDurationMillis()
		);
		this.asyncStub = RemoteControlServiceGrpc.newStub(channel);

		// Scheduler for timeout handling (shared across all calls)
		this.timeoutScheduler = Executors.newScheduledThreadPool(1, r -> {
			Thread t = new Thread(r, "remote-control-timeout");
			t.setDaemon(true);
			return t;
		});

		log.debug("RemoteControlImpl created with channel for {}:{}",
				config.getRemoteControlConfig().getHost(),
				config.getRemoteControlConfig().getPort());
	}

	/**
	 * Factory method to create RemoteControl implementation.
	 * Called by ZequentClientProducer.
	 */
	public static RemoteControl create(GrpcClientConfig config, ManagedChannel channel) {
		return new RemoteControlImpl(config, channel);
	}

	@Override
	public CompletableFuture<TakeoffResponse> takeoff(TakeoffRequest request) {
		validateSn(request.getSn());
		validateCoordinates(request.getLatitude(), request.getLongitude(), request.getAltitude());
		log.info("Takeoff: sn={}", request.getSn());

		var protoRequest = CoordinateCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setCoordinate(GeoCoordinate.newBuilder()
						.setLatitude(request.getLatitude())
						.setLongitude(request.getLongitude())
						.setAltitude(request.getAltitude())
						.build())
				.build();

		return executeAsync(observer -> asyncStub.takeOff(protoRequest, observer))
				.thenApply(proto -> toTakeoffResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> goTo(GoToRequest request) {
		validateSn(request.getSn());
		validateCoordinates(request.getLatitude(), request.getLongitude(), request.getAltitude());
		log.info("GoTo: sn={}", request.getSn());

		var protoRequest = CoordinateCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setCoordinate(GeoCoordinate.newBuilder()
						.setLatitude(request.getLatitude())
						.setLongitude(request.getLongitude())
						.setAltitude(request.getAltitude())
						.build())
				.build();

		return executeAsync(observer -> asyncStub.goTo(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> returnToHome(
			ReturnToHomeRequest request) {
		validateSn(request.getSn());
		log.info("ReturnToHome: sn={}", request.getSn());

		var rthBuilder = com.zqnt.utils.devicecontrol.proto.ReturnToHomeRequest.newBuilder();
		if (request.getAltitude() != null) {
			rthBuilder.setAltitude(request.getAltitude());
		}

		var protoRequest = ReturnToHomeCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setRequest(rthBuilder.build())
				.build();

		return executeAsync(observer -> asyncStub.returnToHome(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> lookAt(LookAtRequest request) {
		validateSn(request.getSn());
		validateCoordinates(request.getLatitude(), request.getLongitude(), request.getAltitude());
		log.info("LookAt: sn={}", request.getSn());

		var protoRequest = LookAtCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setCoordinate(GeoCoordinate.newBuilder()
						.setLatitude(request.getLatitude())
						.setLongitude(request.getLongitude())
						.setAltitude(request.getAltitude())
						.build())
				.build();

		return executeAsync(observer -> asyncStub.lookAt(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> enterManualControl(
			ManualControlRequest request) {
		validateSn(request.getSn());
		if (request.getClientId() == null || request.getClientId().isBlank()) {
			throw new IllegalArgumentException("clientId must not be null or blank");
		}
		if (request.getUserId() == null || request.getUserId().isBlank()) {
			throw new IllegalArgumentException("userId must not be null or blank");
		}
		if (request.getSessionId() == null || request.getSessionId().isBlank()) {
			throw new IllegalArgumentException("sessionId must not be null or blank");
		}
		log.info("EnterManualControl: sn={}", request.getSn());

		var manualControlBuilder = com.zqnt.utils.devicecontrol.proto.ManualControlRequest.newBuilder()
				.setClientId(request.getClientId())
				.setUserId(request.getUserId())
				.setSessionId(request.getSessionId());

		if (request.getReason() != null) {
			manualControlBuilder.setReason(request.getReason());
		}

		var protoRequest = ManualControlCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setRequest(manualControlBuilder.build())
				.build();

		return executeAsync(observer -> asyncStub.enterManualControl(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> exitManualControl(
			ManualControlRequest request) {
		validateSn(request.getSn());
		if (request.getClientId() == null || request.getClientId().isBlank()) {
			throw new IllegalArgumentException("clientId must not be null or blank");
		}
		if (request.getUserId() == null || request.getUserId().isBlank()) {
			throw new IllegalArgumentException("userId must not be null or blank");
		}
		if (request.getSessionId() == null || request.getSessionId().isBlank()) {
			throw new IllegalArgumentException("sessionId must not be null or blank");
		}
		log.info("ExitManualControl: sn={}", request.getSn());

		var manualControlBuilder = com.zqnt.utils.devicecontrol.proto.ManualControlRequest.newBuilder()
				.setClientId(request.getClientId())
				.setUserId(request.getUserId())
				.setSessionId(request.getSessionId());

		if (request.getReason() != null) {
			manualControlBuilder.setReason(request.getReason());
		}

		var protoRequest = ManualControlCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setRequest(manualControlBuilder.build())
				.build();

		return executeAsync(observer -> asyncStub.exitManualControl(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public ManualControlInputSession startManualControlInput(String sn, String assetId) {
		validateSn(sn);
		log.info("Starting manual control input session for SN: {}", sn);

		// CompletableFuture to capture the final response
		var responseFuture = new CompletableFuture<CommandResponse>();

		// Response observer to handle server responses
		StreamObserver<CommandResponse> responseObserver =
			new StreamObserver<>() {
				@Override
				public void onNext(CommandResponse response) {
					responseFuture.complete(response);
				}

				@Override
				public void onError(Throwable t) {
					log.error("Manual control input stream error", t);
					responseFuture.completeExceptionally(t);
				}

				@Override
				public void onCompleted() {
					log.debug("Manual control input stream completed");
				}
			};

		// Start bidirectional streaming - returns request observer
		StreamObserver<ManualControlInputCommandRequest> requestObserver =
			asyncStub.manualControlInput(responseObserver);

		return new ManualControlInputSessionImpl(sn, config.getRequestTimeoutSeconds(), responseFuture, requestObserver);
	}

	@Override
	public CompletableFuture<RemoteControlResponse> openCover(DockOperationRequest request) {
		validateSn(request.getSn());
		log.info("OpenCover: sn={}", request.getSn());

		var protoRequest = EmptyCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.build();

		return executeAsync(observer -> asyncStub.openCover(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> closeCover(DockOperationRequest request) {
		validateSn(request.getSn());
		log.info("CloseCover: sn={}, force={}", request.getSn(), request.getValue());

		var builder = CloseCoverCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()));

		if (request.getValue() != null) {
			builder.setForce(request.getValue());
		}

		return executeAsync(observer -> asyncStub.closeCover(builder.build(), observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> startCharging(DockOperationRequest request) {
		validateSn(request.getSn());
		log.info("StartCharging: sn={}", request.getSn());

		var protoRequest = EmptyCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.build();

		return executeAsync(observer -> asyncStub.startCharging(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> stopCharging(DockOperationRequest request) {
		validateSn(request.getSn());
		log.info("StopCharging: sn={}", request.getSn());

		var protoRequest = EmptyCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.build();

		return executeAsync(observer -> asyncStub.stopCharging(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> rebootAsset(DockOperationRequest request) {
		validateSn(request.getSn());
		log.info("RebootAsset: sn={}", request.getSn());

		var protoRequest = EmptyCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.build();

		return executeAsync(observer -> asyncStub.rebootAsset(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> bootSubAsset(DockOperationRequest request) {
		validateSn(request.getSn());
		// No value means "boot": treating it as false would power the sub-asset down instead.
		boolean bootUp = request.getValue() == null || request.getValue();
		log.info("BootSubAsset: sn={}, boot={}", request.getSn(), bootUp);

		var protoRequest = ToggleCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setEnabled(bootUp)
				.build();

		return executeAsync(observer -> asyncStub.bootSubAsset(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> debugMode(DockOperationRequest request) {
		validateSn(request.getSn());
		log.info("DebugMode: sn={}, enabled={}", request.getSn(), request.getValue());

		var protoRequest = ToggleCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setEnabled(request.getValue() != null && request.getValue())
				.build();

		return executeAsync(observer -> asyncStub.setRemoteDebugMode(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> changeAcMode(DockOperationRequest request) {
		validateSn(request.getSn());
		if (request.getAcMode() == null) {
			throw new IllegalArgumentException("acMode is required");
		}
		log.info("ChangeAcMode: sn={}, mode={}", request.getSn(), request.getAcMode());

		var protoRequest = ChangeAcModeCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.setMode(request.getAcMode().toProto())
				.build();

		return executeAsync(observer -> asyncStub.changeAcMode(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> takePhoto(DockOperationRequest request) {
		validateSn(request.getSn());
		var protoRequest = EmptyCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn()))
				.build();
		return executeAsync(observer -> asyncStub.capturePhoto(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<RemoteControlResponse> liveStreamSplitScreen(LiveStreamSplitScreenRequest request) {
		validateSn(request.getSn());
		log.info("LiveStreamSplitScreen: sn={}, enabled={}", request.getSn(), request.isEnabled());

		var protoRequest = ToggleCommandRequest.newBuilder()
				.setBase(buildBase(request.getSn(), request.getAssetId()))
				.setEnabled(request.isEnabled())
				.build();

		return executeAsync(observer -> asyncStub.liveStreamSplitScreen(protoRequest, observer))
				.thenApply(proto -> toResponse(proto, request.getSn()));
	}

	@Override
	public CompletableFuture<CapabilitySnapshot> getCapabilities(String sn) {
		validateSn(sn);
		AssetCapabilitiesRequest request = AssetCapabilitiesRequest.newBuilder()
				.setSn(sn).setBase(buildBase(sn)).build();
		return executeCapabilitiesAsync(observer -> asyncStub.getCapabilities(request, observer))
				.thenApply(capabilityMapper::fromProto);
	}

	@Override
	public CompletableFuture<com.zqnt.sdk.client.remotecontrol.domains.CustomCommandResponse> sendCustomCommand(
			com.zqnt.sdk.client.remotecontrol.domains.CustomCommandRequest request) {
		var protoRequest = customCommandMapper.toProto(request);

		return executeCustomCommandAsync(observer -> asyncStub.sendCustomCommand(protoRequest, observer))
				.thenApply(proto -> customCommandMapper.fromProto(proto, request.getSn()));
	}

	private CompletableFuture<AssetCapabilitiesResponse> executeCapabilitiesAsync(
			java.util.function.Consumer<StreamObserver<AssetCapabilitiesResponse>> stubCall) {
		CompletableFuture<AssetCapabilitiesResponse> future = new CompletableFuture<>();
		stubCall.accept(new StreamObserver<>() {
			@Override public void onNext(AssetCapabilitiesResponse value) { future.complete(value); }
			@Override public void onError(Throwable error) { future.completeExceptionally(error); }
			@Override public void onCompleted() { }
		});
		return future;
	}

	private static void validateSn(String sn) {
		if (sn == null || sn.isBlank()) {
			throw new IllegalArgumentException("SN must not be null or blank");
		}
	}

	private static void validateCoordinates(float latitude, float longitude, float altitude) {
		if (Float.isNaN(latitude) || Float.isInfinite(latitude)) {
			throw new IllegalArgumentException("Latitude must be a finite number, got: " + latitude);
		}
		if (Float.isNaN(longitude) || Float.isInfinite(longitude)) {
			throw new IllegalArgumentException("Longitude must be a finite number, got: " + longitude);
		}
		if (Float.isNaN(altitude) || Float.isInfinite(altitude)) {
			throw new IllegalArgumentException("Altitude must be a finite number, got: " + altitude);
		}
		if (latitude < -90f || latitude > 90f) {
			throw new IllegalArgumentException("Latitude must be between -90 and 90, got: " + latitude);
		}
		if (longitude < -180f || longitude > 180f) {
			throw new IllegalArgumentException("Longitude must be between -180 and 180, got: " + longitude);
		}
	}

	private com.zqnt.utils.common.proto.RequestBase buildBase(String sn) {
		return buildBase(sn, null);
	}

	private com.zqnt.utils.common.proto.RequestBase buildBase(String sn, String assetId) {
		var builder = RequestBase.newBuilder()
				.setSn(sn)
				.setTid(UUID.randomUUID().toString())
				.setTimestamp(ProtobufHelpers.now());
		if (assetId != null && !assetId.isBlank()) {
			builder.setAssetId(assetId);
		}

		return builder.build();
	}

	/**
	 * Execute async gRPC call with resilience and timeout using StreamObserver pattern.
	 * AsyncStub is the most performant approach (callback-based, non-blocking).
	 */
	private CompletableFuture<CommandResponse> executeAsync(
			java.util.function.Consumer<StreamObserver<CommandResponse>> stubCall) {
		int timeout = config.getRequestTimeoutSeconds();

		return resilience.executeWithResilienceAsync(() -> {
			CompletableFuture<CommandResponse> future = new CompletableFuture<>();
			AtomicBoolean completed = new AtomicBoolean(false);

			// Set timeout
			ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
				if (completed.compareAndSet(false, true)) {
					future.completeExceptionally(new TimeoutException("Remote control request timed out after " + timeout + "s"));
				}
			}, timeout, TimeUnit.SECONDS);

			// StreamObserver for callback-based async handling
			StreamObserver<CommandResponse> observer = new StreamObserver<>() {
				@Override
				public void onNext(CommandResponse response) {
					if (completed.compareAndSet(false, true)) {
						timeoutTask.cancel(false);
						future.complete(response);
					}
				}

				@Override
				public void onError(Throwable t) {
					if (completed.compareAndSet(false, true)) {
						timeoutTask.cancel(false);
						log.error("Remote control request failed: {}", t.getMessage(), t);
						future.completeExceptionally(t);
					}
				}

				@Override
				public void onCompleted() {
					// Response handled in onNext
				}
			};

			// Execute the stub call
			stubCall.accept(observer);
			return future;
		});
	}

	private CompletableFuture<com.zqnt.utils.devicecontrol.proto.CustomCommandResponse> executeCustomCommandAsync(
			java.util.function.Consumer<StreamObserver<com.zqnt.utils.devicecontrol.proto.CustomCommandResponse>> stubCall) {
		int timeout = config.getRequestTimeoutSeconds();
		return resilience.executeWithResilienceAsync(() -> {
			CompletableFuture<com.zqnt.utils.devicecontrol.proto.CustomCommandResponse> future = new CompletableFuture<>();
			AtomicBoolean completed = new AtomicBoolean(false);
			ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
				if (completed.compareAndSet(false, true)) {
					future.completeExceptionally(
							new TimeoutException("Custom command timed out after " + timeout + "s"));
				}
			}, timeout, TimeUnit.SECONDS);

			StreamObserver<com.zqnt.utils.devicecontrol.proto.CustomCommandResponse> observer = new StreamObserver<>() {
				@Override
				public void onNext(com.zqnt.utils.devicecontrol.proto.CustomCommandResponse response) {
					if (completed.compareAndSet(false, true)) {
						timeoutTask.cancel(false);
						future.complete(response);
					}
				}

				@Override
				public void onError(Throwable error) {
					if (completed.compareAndSet(false, true)) {
						timeoutTask.cancel(false);
						future.completeExceptionally(error);
					}
				}

				@Override public void onCompleted() { }
			};
			stubCall.accept(observer);
			return future;
		});
	}

	/**
	 * Shutdown executors when done.
	 * Should be called when closing the client.
	 */
	public void shutdown() {
		timeoutScheduler.shutdown();
		try {
			if (!timeoutScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				timeoutScheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			timeoutScheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private TakeoffResponse toTakeoffResponse(CommandResponse proto, String sn) {
		var meta = proto.getMeta();
		return TakeoffResponse.builder()
				.success(!proto.getHasErrors())
				.sn(sn)
				.tid(meta.getTid())
				.message(meta.hasResponseMessage() ? meta.getResponseMessage() : null)
				.assetId(meta.hasAssetId() ? meta.getAssetId() : null)
				.error(proto.hasError() ? TakeoffResponse.ErrorInfo.builder()
						.errorCode(proto.getError().getErrorCode().name())
						.errorMessage(proto.getError().getErrorMessage())
						.timestamp(ProtobufHelpers.toLocalDateTime(proto.getError().getTimestamp()))
						.build() : null)
				.progress(proto.hasProgress() ? TakeoffResponse.ProgressInfo.builder()
						.progress(proto.getProgress().getProgress())
						.state(proto.getProgress().getState())
						.leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds())
						.build() : null)
				.build();
	}

	private RemoteControlResponse toResponse(
			CommandResponse proto, String sn) {
		var meta = proto.getMeta();
		return RemoteControlResponse.builder()
				.success(!proto.getHasErrors())
				.sn(sn)
				.tid(meta.getTid())
				.message(meta.hasResponseMessage() ? meta.getResponseMessage() : null)
				.assetId(meta.hasAssetId() ? meta.getAssetId() : null)
				.error(proto.hasError() ? RemoteControlResponse.ErrorInfo.builder()
						.errorCode(proto.getError().getErrorCode().name())
						.errorMessage(proto.getError().getErrorMessage())
						.timestamp(ProtobufHelpers.toLocalDateTime(proto.getError().getTimestamp()))
						.build() : null)
				.progress(proto.hasProgress() ? RemoteControlResponse.ProgressInfo.builder()
						.progress(proto.getProgress().getProgress())
						.state(proto.getProgress().getState())
						.leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds())
						.build() : null)
				.build();
	}

}
