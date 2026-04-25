package dev.toganbayev.emailnotificationmicroservice.controller;

import dev.toganbayev.emailnotificationmicroservice.persistence.entity.ProcessedEventEntity;
import dev.toganbayev.emailnotificationmicroservice.persistence.repository.ProcessedEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class DataController {

    private final ProcessedEventRepository repository;

    public DataController(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ProcessedEventEntity> getAllProcessedEvents() {
        return repository.findAll();
    }
}
