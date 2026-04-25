package dev.toganbayev.emailnotificationmicroservice.handler;

import dev.toganbayev.core.ProductCreatedEvent;
import dev.toganbayev.emailnotificationmicroservice.exception.NonRetryableException;
import dev.toganbayev.emailnotificationmicroservice.exception.RetryableException;
import dev.toganbayev.emailnotificationmicroservice.persistence.entity.ProcessedEventEntity;
import dev.toganbayev.emailnotificationmicroservice.persistence.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

// Consumes product creation events from Kafka and triggers external notification service
@Component
@KafkaListener(topics = "product-created-events-topic")
public class ProductCreatedEventHandler {

    // Logger for tracking event processing and errors
    private final Logger LOGGER = LoggerFactory.getLogger(ProductCreatedEventHandler.class);

    private final RestTemplate restTemplate;
    private final ProcessedEventRepository processedEventRepository;

    public ProductCreatedEventHandler(RestTemplate restTemplate, ProcessedEventRepository processedEventRepository) {
        this.restTemplate = restTemplate;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    // Handles incoming product events and notifies external service
    @KafkaHandler
    public void handle(@Payload ProductCreatedEvent productCreatedEvent,
                       @Header("messageId") String messageId,
                       @Header(KafkaHeaders.RECEIVED_KEY) String messageKey) {
        LOGGER.info("Received event: {}, productId: {}", productCreatedEvent.getTitle(), productCreatedEvent.getProductId());

        ProcessedEventEntity processedEventEntity = processedEventRepository.findByMessageId(messageId);

        if (processedEventEntity != null) {
            LOGGER.info("Duplicate message id: {}", messageId);
            return;
        }

        try {
            // External service endpoint for sending notifications
            String url = "http://localhost:8090/response/200";
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

        try {
            processedEventRepository.save(new ProcessedEventEntity(messageId, productCreatedEvent.getProductId()));
        } catch (DataIntegrityViolationException e) {
            LOGGER.error(e.getMessage());
            throw new NonRetryableException(e);
        }
    }
}
