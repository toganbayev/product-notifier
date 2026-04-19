package dev.toganbayev.emailnotificationmicroservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

// Provides beans for external service communication (notification service calls)
@Configuration
public class EmailNotificationConfig {

    // RestTemplate for synchronous HTTP requests to notification service
    @Bean
    RestTemplate getrestTemplate() {
        return new RestTemplate();
    }
}
