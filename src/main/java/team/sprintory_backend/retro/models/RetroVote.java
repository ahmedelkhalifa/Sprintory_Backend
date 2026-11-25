package team.sprintory_backend.retro.models;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.user.models.User;

/**
 * One person's vote on one card (R31). At most one per card ({@code uq_retro_votes_item_user});
 * the limit of 3 per retro is checked by the service under the retro row lock. Removing a vote
 * deletes the row. {@code retro} is stored next to the card so the per-retro count needs no join.
 * Not {@code @Immutable}: deleting a card removes its votes with a bulk delete, which Hibernate
 * refuses on immutable entities.
 */
@Entity
@Table(name = "retro_votes", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "user_id"}))
@Getter
@NoArgsConstructor
public class RetroVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retro_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Retrospective retro;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RetroItem item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    public RetroVote(RetroItem item, User user) {
        this.retro = item.getRetro();
        this.item = item;
        this.user = user;
    }
}
