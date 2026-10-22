package team.sprintory_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SprintoryBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SprintoryBackendApplication.class, args);
    }

}
