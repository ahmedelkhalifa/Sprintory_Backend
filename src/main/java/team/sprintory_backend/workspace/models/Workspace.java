package team.sprintory_backend.workspace.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import team.sprintory_backend.audit.BaseEntity;

import java.time.Instant;

/** Top-level container for an organization's teams and projects (R02). */
@Entity
@Table(name = "workspaces")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Workspace extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** Generated from the name at creation and never changed, so links stay stable. */
    @Column(nullable = false, unique = true, updatable = false, length = 100)
    private String slug;

    /** Null while active. An archived workspace is read-only until an admin restores it. */
    private Instant archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Workspace other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}