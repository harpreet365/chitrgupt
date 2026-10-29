package com.bms.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "customer_profiles")
public class CustomerProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", unique = true, updatable = false)
    private User user;

    @Column(name = "customer_number", nullable = false, unique = true, updatable = false)
    private String customerNumber;

    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "address_line")
    private String addressLine;

    private String city;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Version
    private long version;

    protected CustomerProfile() {}

    public CustomerProfile(User user, String customerNumber, Long createdBy) {
        this.user = user;
        this.customerNumber = customerNumber;
        this.createdBy = createdBy;
    }

    @PreUpdate
    void touch() { this.updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getCustomerNumber() { return customerNumber; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate d) { this.dateOfBirth = d; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String a) { this.addressLine = a; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String p) { this.postalCode = p; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
