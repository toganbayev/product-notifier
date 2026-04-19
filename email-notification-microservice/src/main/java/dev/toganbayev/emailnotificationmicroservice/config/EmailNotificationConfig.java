package dev.toganbayev.emailnotificationmicroservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class EmailNotificationConfig {

    @Bean
    RestTemplate getrestTemplate() {
        return new RestTemplate();
    }
}
