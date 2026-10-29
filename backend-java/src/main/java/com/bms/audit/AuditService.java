package com.bms.audit;

import com.bms.domain.AuditLog;
import com.bms.domain.User;
import com.bms.repo.AuditLogRepository;
import com.bms.security.AppUserPrincipal;
import com.bms.security.ClientInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Append-only audit trail. Callers pass only non-secret details: never passwords, hashes, tokens or session ids.
 * Audit rows are written in the caller's database transaction, so they commit or roll back with the action.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private final AuditLogRepository repo;
    private final ObjectMapper mapper;

    public AuditService(AuditLogRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public void record(String action, String targetType, Object targetId, Map<String, ?> details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            write(p.id(), p.username(), p.role().name(), action, targetType, targetId, details);
        } else {
            write(null, "system", null, action, targetType, targetId, details);
        }
    }

    public void recordAs(User actor, String action, String targetType, Object targetId, Map<String, ?> details) {
        write(actor.getId(), actor.getUsername(), actor.roleName().name(), action, targetType, targetId, details);
    }

    private void write(Long actorId, String actorName, String role, String action, String targetType,
                       Object targetId, Map<String, ?> details) {
        String json = null;
        if (details != null && !details.isEmpty()) {
            try {
                json = mapper.writeValueAsString(details);
            } catch (JsonProcessingException e) {
                json = "{\"error\":\"details not serialisable\"}";
            }
        }
        String target = targetId == null ? null : String.valueOf(targetId);
        repo.save(new AuditLog(actorId, actorName, role, action, targetType, target, json, ClientInfo.ip(),
                OffsetDateTime.now()));
        log.info("action={} actor={} target={}:{}", action, actorName, targetType, target);
    }
}
