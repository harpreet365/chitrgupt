package com.bms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "roles")
public class Role {
    @Id
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private RoleName name;

    @Column(nullable = false)
    private String description;

    protected Role() {}

    public Short getId() { return id; }
    public RoleName getName() { return name; }
    public String getDescription() { return description; }
}
