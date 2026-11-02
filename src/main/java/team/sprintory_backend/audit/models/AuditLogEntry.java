package team.sprintory_backend.audit.models;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * One entry of a workspace's audit log (R05). Append-only: {@code @Immutable} and no setters, so
 * nothing can change it after the insert. Names are snapshots taken when the action happened;
 * actor and project are plain ids without a foreign key so the entry outlives them.
 * Contract: docs/audit-log-docs.md.
 */
@Entity
@Immutable
@Table(name = "audit_log_entries")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Workspace workspace;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private AuditAction action;

    /** Null when the system acted (LOGIN_FAILED_REPEATED); then the name and email are null too. */
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_name", updatable = false, length = 512)
    private String actorName;

    @Column(name = "actor_email", updatable = false)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 20)
    private AuditTargetType targetType;

    /** Null only for an INVITATION target. */
    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(name = "target_name", nullable = false, updatable = false, length = 512)
    private String targetName;

    /** Set for project and task actions only. */
    @Column(name = "project_id", updatable = false)
    private Long projectId;

    @Column(name = "project_name", updatable = false, length = 100)
    private String projectName;

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "audit_log_changes", joinColumns = @JoinColumn(name = "entry_id"))
    @OrderColumn(name = "sort_order")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<AuditChange> changes = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditLogEntry other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}