package com.zqnt.sdk.client.testdata;

import com.zqnt.utils.mission.proto.*;
import com.zqnt.utils.missionautonomy.domains.*;
import com.zqnt.utils.missionautonomy.domains.config.WaypointTaskConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MissionNfzTestData {

    public static final UUID OPERATION_ID = UUID.fromString(
            "019f837b-22ac-7500-abac-5db42ce5d921");
    public static final String MISSION_ID = "822a22ef-bace-4bb6-ae0d-d03b60d848af";
    public static final double ROUTE_START_LATITUDE = 47.775300;
    public static final double ROUTE_START_LONGITUDE = 9.267800;
    public static final double ROUTE_DESTINATION_LATITUDE = 47.776583;
    public static final double ROUTE_DESTINATION_LONGITUDE = 9.269973;

    private static final List<GeoPointDTO> POLYGON_POINTS = List.of(
            point(47.776410538, 9.268271094),
            point(47.775511245, 9.268249301),
            point(47.775709525, 9.269547239),
            point(47.776501565, 9.269317826),
            point(47.776410538, 9.268271094));

    private MissionNfzTestData() {
    }

    public static MissionZoneDTO zone() {
        return MissionZoneDTO.builder()
                .id(OPERATION_ID)
                .name("Test Sperrzone Zqnt")
                .type(MissionZoneType.MISSION_ZONE_TYPE_NO_FLY)
                .enforcementType(ZoneEnforcementType.ZONE_ENFORCEMENT_TYPE_HARD_BLOCK)
                .area(GeoAreaDTO.builder()
                        .type(GeoAreaType.GEO_AREA_TYPE_POLYGON)
                        .vertices(POLYGON_POINTS)
                        .build())
                .active(true)
                .priority(0)
                .config(DynamicConfigDTO.builder()
                        .templateId("zqnt-nfz")
                        .templateConfig(Map.ofEntries(
                                Map.entry("description", "Test Sperrzone"),
                                Map.entry("zoneType", "AIR"),
                                Map.entry("restrictionType", "FORBIDDEN"),
                                Map.entry("minAltitude", 0),
                                Map.entry("maxAltitude", 0),
                                Map.entry("warningDistance", 10),
                                Map.entry("accessLevel", 0),
                                Map.entry("createdBy", "seke"),
                                Map.entry("lastModifiedBy", "seke"),
                                Map.entry("operationId", OPERATION_ID.toString()),
                                Map.entry("serverCreateTime", "2026-08-05T11:12:22.525728Z"),
                                Map.entry("serverUpdateTime", "2026-08-05T11:12:22.525728Z"),
                                Map.entry("baseId", MISSION_ID)))
                        .build())
                .build();
    }

    public static List<GeoPointDTO> polygonPoints() {
        return POLYGON_POINTS;
    }

    public static TaskDTO reroutingTask(String missionId, String assetSn) {
        String externalTaskId = "nfz-reroute-" + UUID.randomUUID();
        return TaskDTO.builder()
                .missionId(UUID.fromString(missionId))
                .name("NFZ rerouting integration test")
                .description("Direct route crosses Test Sperrzone Zqnt")
                .taskType(TaskTypeProto.TASK_TYPE_WAYPOINT)
                .status(TaskStatus.TASK_DRAFT)
                .snNumber(assetSn)
                .externalTaskId(externalTaskId)
                .config(WaypointTaskConfig.builder()
                        .externalTaskId(externalTaskId)
                        .rthAltitude(70)
                        .rthMode(RthModeEnumProto.RTH_MODE_PRESET)
                        .waypoints(List.of(
                                waypoint(ROUTE_START_LATITUDE, ROUTE_START_LONGITUDE, 50.0f, 0),
                                waypoint(ROUTE_DESTINATION_LATITUDE, ROUTE_DESTINATION_LONGITUDE, 50.0f, 1)))
                        .build())
                .build();
    }

    private static GeoPointDTO point(double latitude, double longitude) {
        return GeoPointDTO.builder().latitude(latitude).longitude(longitude).build();
    }

    private static WaypointDTO waypoint(double latitude, double longitude, float altitude, int order) {
        return WaypointDTO.builder()
                .latitude(latitude)
                .longitude(longitude)
                .altitude(altitude)
                .wpOrder(order)
                .build();
    }
}
