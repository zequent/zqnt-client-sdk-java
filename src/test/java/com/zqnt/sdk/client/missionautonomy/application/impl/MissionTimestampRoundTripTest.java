package com.zqnt.sdk.client.missionautonomy.application.impl;

import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.mission.proto.TaskProtoDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Timestamps used to come back shifted by the caller's own UTC offset. Outbound,
 * {@link ProtobufHelpers#toTimestamp} applies the system zone; inbound, the proto-to-JSON-to-DTO
 * round trip rendered the instant as an RFC-3339 UTC string that Jackson parsed into a
 * {@code LocalDateTime} by discarding the offset. A value sent as 13:56 from a UTC+2 machine came
 * back as 11:56, and a scheduler built from it would have fired two hours early.
 *
 * <p>The default zone is forced to a non-UTC one for these tests: on a UTC CI runner the encode and
 * decode paths happen to agree, so the regression would go unnoticed there.
 */
class MissionTimestampRoundTripTest {

    private TimeZone original;

    @BeforeEach
    void useNonUtcZone() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(original);
    }

    @Test
    void taskTimestampsSurviveTheProtoRoundTripUnshifted() {
        LocalDateTime created = LocalDateTime.of(2026, 9, 10, 13, 56, 55, 338046200);
        LocalDateTime modified = LocalDateTime.of(2026, 9, 10, 14, 30, 0, 0);

        TaskProtoDTO proto = TaskProtoDTO.newBuilder()
                .setCreatedAt(ProtobufHelpers.toTimestamp(created))
                .setModifiedAt(ProtobufHelpers.toTimestamp(modified))
                .build();

        var task = MissionAutonomyImpl.mapTaskProtoToDto(proto);

        assertEquals(created, task.getCreatedAt(),
                "createdAt must round-trip unchanged, whatever zone the caller runs in");
        assertEquals(modified, task.getModifiedAt());
    }

    @Test
    void absentTaskTimestampsStayNull() {
        var task = MissionAutonomyImpl.mapTaskProtoToDto(TaskProtoDTO.newBuilder().build());

        assertNull(task.getCreatedAt());
        assertNull(task.getModifiedAt());
    }
}
