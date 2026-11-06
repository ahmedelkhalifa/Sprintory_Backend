package team.sprintory_backend.project.charter.models;

import jakarta.persistence.*;
import lombok.*;
import team.sprintory_backend.project.models.Project;

@Entity
@Table(name = "charter_goals")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CharterGoal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "success_measure", length = 1000)
    private String successMeasure;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
