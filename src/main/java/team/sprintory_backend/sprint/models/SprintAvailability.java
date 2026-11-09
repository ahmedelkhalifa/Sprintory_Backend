package team.sprintory_backend.sprint.models;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import team.sprintory_backend.user.models.User;

@Entity @Table(name = "sprint_availability", uniqueConstraints = @UniqueConstraint(columnNames = {"sprint_id", "user_id"}))
@Getter @Setter @NoArgsConstructor
public class SprintAvailability {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Sprint sprint;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;
    @Column(nullable = false)
    private int availableDays;

    public SprintAvailability(Sprint sprint, User user, int availableDays) {
        this.sprint = sprint;
        this.user = user;
        this.availableDays = availableDays;
    }
}
