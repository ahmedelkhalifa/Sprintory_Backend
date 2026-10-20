package team.sprintory_backend.user.models;

import jakarta.persistence.*;
import lombok.*;
import team.sprintory_backend.audit.BaseEntity;

import java.util.Objects;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;
    @Column(nullable = false)
    private String lastName;
    @Column(nullable = false, unique = true)
    private String email;
    private String password;
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    private Provider provider;
    @Enumerated(EnumType.STRING)
    private Role role;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
