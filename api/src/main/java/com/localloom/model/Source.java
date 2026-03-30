package com.localloom.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sources")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "origin_url")
    private String originUrl;

    @Column(name = "icon_url")
    private String iconUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String config;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status")
    private SyncStatus syncStatus = SyncStatus.IDLE;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "source", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ContentUnit> contentUnits = new ArrayList<>();

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(final UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(final SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getOriginUrl() {
        return originUrl;
    }

    public void setOriginUrl(final String originUrl) {
        this.originUrl = originUrl;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(final String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(final String config) {
        this.config = config;
    }

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(final SyncStatus syncStatus) {
        this.syncStatus = syncStatus;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(final Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ContentUnit> getContentUnits() {
        return contentUnits;
    }

    public void setContentUnits(final List<ContentUnit> contentUnits) {
        this.contentUnits = contentUnits;
    }

    public void addContentUnit(final ContentUnit unit) {
        contentUnits.add(unit);
        unit.setSource(this);
    }

    public void removeContentUnit(final ContentUnit unit) {
        contentUnits.remove(unit);
        unit.setSource(null);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof Source other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
