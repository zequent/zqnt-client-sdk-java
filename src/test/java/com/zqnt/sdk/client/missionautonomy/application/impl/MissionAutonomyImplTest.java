package com.zqnt.sdk.client.missionautonomy.application.impl;

import com.zqnt.sdk.client.testdata.MissionNfzTestData;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.mission.proto.*;
import com.zqnt.utils.missionautonomy.domains.*;
import com.zqnt.utils.missionautonomy.domains.config.PoiTaskConfig;
import com.zqnt.utils.missionautonomy.domains.config.WaypointTaskConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MissionAutonomyImplTest {

    @Test
    void mapsTypeSafeTaskConfigAndExecutionFields() {
        TaskDTO task = new TaskDTO();
        task.setName("Inspect POI");
        task.setConfig(PoiTaskConfig.builder()
                .poiLatitude(47.1)
                .poiLongitude(8.2)
                .poiAltitude(25f)
                .numberOfOrbits(2)
                .build());
        task.setExternalTaskId("external-1");
        task.setExecutionOrder(3);
        task.setDecisionEngineEnabled(true);

        TaskProtoDTO mapped = MissionAutonomyImpl.mapTaskDtoToProto(TaskProtoDTO.newBuilder(), task).build();

        assertTrue(mapped.hasPoiConfig());
        assertEquals(47.1, mapped.getPoiConfig().getPoiLatitude());
        assertEquals(2, mapped.getPoiConfig().getNumberOfOrbits());
        assertEquals("external-1", mapped.getExternalTaskId());
        assertEquals(3, mapped.getExecutionOrder());
        assertTrue(mapped.getDecisionEngineEnabled());
    }

    @Test
    void mapsWaypointProtoConfigBackToTaskDto() {
        TaskProtoDTO proto = TaskProtoDTO.newBuilder()
                .setName("Rerouted task")
                .setWaypointConfig(WaypointTaskConfigProto.newBuilder()
                        .addWaypoints(WaypointProtoDTO.newBuilder()
                                .setLatitude(47.7753).setLongitude(9.2678).setWpOrder(0))
                        .addWaypoints(WaypointProtoDTO.newBuilder()
                                .setLatitude(47.776583).setLongitude(9.269973).setWpOrder(1)))
                .build();

        TaskDTO mapped = MissionAutonomyImpl.mapTaskProtoToDto(proto);

        WaypointTaskConfig config = assertInstanceOf(WaypointTaskConfig.class, mapped.getConfig());
        assertEquals(2, config.getWaypoints().size());
        assertEquals(47.776583, config.getWaypoints().getLast().getLatitude());
        assertEquals(9.269973, config.getWaypoints().getLast().getLongitude());
    }

    @Test
    void mapsNewMissionConfigurationAndNestedTasks() {
        TaskDTO task = new TaskDTO();
        task.setName("Nested task");
        MissionDTO mission = new MissionDTO();
        mission.setName("Mission");
        mission.setDescription("Description");
        mission.setExternalId("external-mission");
        mission.setExternalMissionType("vendor-type");
        mission.setMissionConfig(DynamicConfigDTO.builder()
                .templateId("template-1")
                .templateConfig(Map.of("speed", 12.5))
                .build());
        mission.setTasks(java.util.List.of(task));

        MissionProtoDTO mapped = MissionAutonomyImpl
                .mapMissionDtoToProto(MissionProtoDTO.newBuilder(), mission)
                .build();

        assertEquals("external-mission", mapped.getExternalId());
        assertEquals("vendor-type", mapped.getExternalMissionType());
        assertEquals("template-1", mapped.getMissionConfig().getTemplateId());
        assertEquals(12.5, mapped.getMissionConfig().getTemplateConfig().getFieldsOrThrow("speed").getNumberValue());
        assertEquals("Nested task", mapped.getTasks(0).getName());
    }

    @Test
    void mapsSchedulerAuditTimestamps() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 25, 12, 30);
        SchedulerDTO scheduler = new SchedulerDTO();
        scheduler.setName("Daily");
        scheduler.setCronExpression("0 0 * * *");
        scheduler.setCreatedAt(createdAt);
        scheduler.setModifiedAt(createdAt.plusHours(1));

        SchedulerProtoDTO mapped = MissionAutonomyImpl
                .mapSchedulerDtoToProto(SchedulerProtoDTO.newBuilder(), scheduler)
                .build();

        assertTrue(mapped.hasCreatedAt());
        assertTrue(mapped.hasModifiedAt());
    }

    @Test
    void mapsMissionNfzZoneUploadRequest() {
        var zone = MissionNfzTestData.zone();
        var polygonPoints = MissionNfzTestData.polygonPoints();

        var mapped = MissionAutonomyImpl.mapUploadMissionNfzZonesRequest(
                RequestBase.newBuilder().setTid("tid-1").build(),
                MissionNfzTestData.MISSION_ID,
                List.of(zone),
                true);

        assertEquals("tid-1", mapped.getBase().getTid());
        assertEquals(MissionNfzTestData.MISSION_ID, mapped.getMissionId());
        assertTrue(mapped.getReplaceExisting());
        assertEquals(1, mapped.getZonesCount());
        assertEquals(MissionNfzTestData.OPERATION_ID.toString(), mapped.getZones(0).getId());
        assertEquals("Test Sperrzone Zqnt", mapped.getZones(0).getName());
        assertEquals(MissionZoneType.MISSION_ZONE_TYPE_NO_FLY, mapped.getZones(0).getType());
        assertEquals(ZoneEnforcementType.ZONE_ENFORCEMENT_TYPE_HARD_BLOCK,
                mapped.getZones(0).getEnforcementType());
        assertTrue(mapped.getZones(0).hasArea());
        assertEquals(GeoAreaType.GEO_AREA_TYPE_POLYGON, mapped.getZones(0).getArea().getType());
        assertTrue(mapped.getZones(0).getActive());
        assertTrue(mapped.getZones(0).hasPriority());
        assertEquals(0, mapped.getZones(0).getPriority());
        assertEquals("zqnt-nfz", mapped.getZones(0).getConfig().getTemplateId());

        var mappedConfig = mapped.getZones(0).getConfig().getTemplateConfig();
        assertEquals("FORBIDDEN",
                mappedConfig.getFieldsOrThrow("restrictionType").getStringValue());
        assertEquals(10,
                mappedConfig.getFieldsOrThrow("warningDistance").getNumberValue());
        assertEquals(MissionNfzTestData.MISSION_ID,
                mappedConfig.getFieldsOrThrow("baseId").getStringValue());

        var mappedPoints = mapped.getZones(0).getArea().getVerticesList();
        assertEquals(5, mappedPoints.size());
        for (int index = 0; index < polygonPoints.size(); index++) {
            assertEquals(polygonPoints.get(index).getLatitude(), mappedPoints.get(index).getLatitude());
            assertEquals(polygonPoints.get(index).getLongitude(), mappedPoints.get(index).getLongitude());
        }
        assertEquals(mappedPoints.getFirst(), mappedPoints.getLast(),
                "The NFZ polygon must remain closed");
    }

    @Test
    void validatesMissionNfzZoneUploadRequest() {
        var validZone = MissionZoneDTO.builder()
                .name("NFZ")
                .type(MissionZoneType.MISSION_ZONE_TYPE_NO_FLY)
                .enforcementType(ZoneEnforcementType.ZONE_ENFORCEMENT_TYPE_HARD_BLOCK)
                .area(GeoAreaDTO.builder()
                        .type(GeoAreaType.GEO_AREA_TYPE_POLYGON)
                        .vertices(List.of(point(47, 9), point(47, 10), point(48, 10)))
                        .build())
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                MissionAutonomyImpl.mapUploadMissionNfzZonesRequest(
                        RequestBase.getDefaultInstance(), " ", java.util.List.of(validZone), false));
        assertThrows(IllegalArgumentException.class, () ->
                MissionAutonomyImpl.mapUploadMissionNfzZonesRequest(
                        RequestBase.getDefaultInstance(), "mission-1", null, false));
        assertThrows(IllegalArgumentException.class, () ->
                MissionAutonomyImpl.mapUploadMissionNfzZonesRequest(
                        RequestBase.getDefaultInstance(), "mission-1", java.util.Arrays.asList((MissionZoneDTO) null), false));

        var invalidPolygon = MissionZoneDTO.builder()
                .name("Invalid NFZ")
                .type(MissionZoneType.MISSION_ZONE_TYPE_NO_FLY)
                .enforcementType(ZoneEnforcementType.ZONE_ENFORCEMENT_TYPE_HARD_BLOCK)
                .area(GeoAreaDTO.builder()
                        .type(GeoAreaType.GEO_AREA_TYPE_POLYGON)
                        .vertices(List.of(point(47, 9), point(48, 10)))
                        .build())
                .build();
        assertThrows(IllegalArgumentException.class, () ->
                MissionAutonomyImpl.mapUploadMissionNfzZonesRequest(
                        RequestBase.getDefaultInstance(), "mission-1", List.of(invalidPolygon), false));
    }

    private static GeoPointDTO point(double latitude, double longitude) {
        return GeoPointDTO.builder().latitude(latitude).longitude(longitude).build();
    }
}
