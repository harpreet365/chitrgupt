package com.bms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;

@Entity
@Immutable
@Table(name = "login_activity")
public class LoginActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username_attempted", nullable = false)
    private String usernameAttempted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoginOutcome outcome;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LoginActivity() {}

    public LoginActivity(Long userId, String usernameAttempted, LoginOutcome outcome, String ip, String ua,
                         OffsetDateTime createdAt) {
        this.userId = userId;
        this.usernameAttempted = usernameAttempted;
        this.outcome = outcome;
        this.ipAddress = ip;
        this.userAgent = ua;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getUsernameAttempted() { return usernameAttempted; }
    public LoginOutcome getOutcome() { return outcome; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
