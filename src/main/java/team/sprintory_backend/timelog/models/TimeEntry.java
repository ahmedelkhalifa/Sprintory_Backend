package team.sprintory_backend.timelog.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import team.sprintory_backend.audit.BaseEntity;
import java.time.Instant;
import java.time.LocalDate;

/** A personal work log. IDs are stored directly for future workspace reporting. */
@Entity
@Table(name = "time_entry")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeEntry extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false) private Long workspaceId;
    @Column(nullable = false, updatable = false) private Long projectId;
    @Column(nullable = false, updatable = false) private Long taskId;
    @Column(nullable = false, updatable = false) private Long userId;
    @Column(nullable = false) private LocalDate workDate;
    @Column(nullable = false) private int minutes;
    @Column(length = 500) private String note;
    private Instant deletedAt;
}
