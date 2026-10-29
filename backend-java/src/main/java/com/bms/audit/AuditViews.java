package com.bms.audit;

import com.bms.domain.AuditLog;

import java.time.OffsetDateTime;

public final class AuditViews {
    private AuditViews() {}

    public record AuditView(Long id, Long actorUserId, String actorUsername, String actorRole, String action,
                            String targetType, String targetId, String details, String ipAddress,
                            OffsetDateTime createdAt) {
        public static AuditView of(AuditLog a) {
            return new AuditView(a.getId(), a.getActorUserId(), a.getActorUsername(), a.getActorRole(), a.getAction(),
                    a.getTargetType(), a.getTargetId(), a.getDetails(), a.getIpAddress(), a.getCreatedAt());
        }
    }
}
