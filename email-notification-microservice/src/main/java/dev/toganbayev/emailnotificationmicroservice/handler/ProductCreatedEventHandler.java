package dev.toganbayev.emailnotificationmicroservice.handler;

import dev.toganbayev.core.ProductCreatedEvent;
import dev.toganbayev.emailnotificationmicroservice.exception.NonRetryableException;
import dev.toganbayev.emailnotificationmicroservice.exception.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

// Consumes product creation events from Kafka and triggers external notification service
@Component
@KafkaListener(topics = "product-created-events-topic")
public class ProductCreatedEventHandler {

    private final RestTemplate restTemplate;
    // Logger for tracking event processing and errors
    private final Logger LOGGER = LoggerFactory.getLogger(ProductCreatedEventHandler.class);

    public ProductCreatedEventHandler(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // Handles incoming product events and notifies external service
    @KafkaHandler
    public void handle(ProductCreatedEvent productCreatedEvent) {
        LOGGER.info("Received event: {}, productId: {}", productCreatedEvent.getTitle(), productCreatedEvent.getProductId());
        // External service endpoint for sending notifications
        String url = "http://localhost:8090/response/200";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);

            if (response.getStatusCode().value() == HttpStatus.OK.value()) {
                LOGGER.info("Received response {}", response.getBody());
            }
        } catch (ResourceAccessException e) {
            // Network errors are retryable (service temporarily unavailable)
            LOGGER.error(e.getMessage());
            throw new RetryableException(e);
        } catch (HttpServerErrorException e) {
            // Server errors (5xx) are non-retryable (likely a bug in remote service)
            LOGGER.error(e.getMessage());
            throw new NonRetryableException(e);
        } catch (Exception e) {
            // Unknown errors are non-retryable by default to prevent infinite retries
            LOGGER.error(e.getMessage());
            throw new NonRetryableException(e);
        }
    }
}
