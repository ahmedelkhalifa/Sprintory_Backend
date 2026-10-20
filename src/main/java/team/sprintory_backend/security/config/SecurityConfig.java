package team.sprintory_backend.security.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final String frontendUrl = "url";
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;
    private final AppUserDetailsService userDetailsService;
    private final UserRepository userRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("api/v1/auth/**").permitAll()
                .anyRequest().authenticated())
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(c -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOrigins(List.of(frontendUrl));
                    config.addAllowedHeader("*");
                    config.addAllowedMethod("*");
                    config.setAllowCredentials(true);
                    return config;
                }))
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authenticationProvider(authenticationProvider());


        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    CommandLineRunner createAdmin() {
        return args -> {
            if (!userRepository.existsByRole(Role.WORKSPACE_ADMIN)) {
                User admin = User.builder()
                        .email("admin@gmail.com")
                        .password(passwordEncoder().encode("password123"))
                        .role(Role.WORKSPACE_ADMIN)
                        .firstName("admin")
                        .lastName("admin")
                        .enabled(true)
                        .build();
                userRepository.save(admin);
            }
        };
    }
}
