package team.sprintory_backend.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import team.sprintory_backend.user.models.Provider;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

/**
 * Creates the first workspace admin so a fresh dev database is usable.
 * Dev only — never seed a known account into a deployed environment.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.admin-email}")
    private String adminEmail;

    @Value("${app.seed.admin-password}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (userRepository.existsByRole(Role.WORKSPACE_ADMIN)) {
            return;
        }
        String email = adminEmail.trim().toLowerCase();
        userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(adminPassword))
                .role(Role.WORKSPACE_ADMIN)
                .provider(Provider.LOCAL)
                .firstName("admin")
                .lastName("admin")
                .enabled(true)
                .build());
        log.info("Seeded workspace admin {}", email);
    }
}