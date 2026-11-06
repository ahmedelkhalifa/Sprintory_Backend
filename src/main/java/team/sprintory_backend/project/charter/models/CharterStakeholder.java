package team.sprintory_backend.project.charter.models;

import jakarta.persistence.*;
import lombok.*;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

@Entity
@Table(name = "charter_stakeholders")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CharterStakeholder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 200)
    private String organization;

    @Column(name = "project_role", length = 100)
    private String projectRole;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private StakeholderLevel interest;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private StakeholderLevel influence;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_user_id")
    private User linkedUser;
}
