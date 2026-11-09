package team.sprintory_backend.sprint.models;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.project.models.Project;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "sprints", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "sprint_number"}))
@Getter @Setter @NoArgsConstructor
public class Sprint extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;
    @Column(name = "sprint_number", nullable = false, updatable = false)
    private int number;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(columnDefinition = "text", nullable = false)
    private String goal = "";
    @Column
    private Integer capacity;
    private LocalDate startDate;
    private LocalDate endDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SprintStatus status = SprintStatus.PLANNED;
    private Instant completedAt;
    private Integer committedPoints;
    private Integer completedPoints;

    public Sprint(Project project, int number, String name, String goal, Integer capacity, LocalDate startDate, LocalDate endDate) {
        this.project = project;
        this.number = number;
        this.name = name;
        this.goal = goal;
        this.capacity = capacity;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
