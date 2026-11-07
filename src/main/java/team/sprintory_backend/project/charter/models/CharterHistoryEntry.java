package team.sprintory_backend.project.charter.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import team.sprintory_backend.project.models.Project;
import java.time.Instant;

/** Immutable old/new snapshots. Never includes stakeholder email or other contact fields. */
@Entity
@Immutable
@Table(name = "charter_history")
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CharterHistoryEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 400, updatable = false)
    private String actorName;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30, updatable = false)
    private CharterChangeType changeType;

    @Column(name = "old_value", columnDefinition = "text", updatable = false)
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text", updatable = false)
    private String newValue;
}
