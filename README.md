# Product Notifier

Event-driven microservices monorepo for product management and email notifications using Spring Boot, Kafka, and Apache Kafka.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-Enabled-231F20.svg)](https://kafka.apache.org/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-blue.svg)](https://maven.apache.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED.svg)](https://www.docker.com/)

## System Card

| Field | Value |
|------:|------|
| **Project Name** | Product Notifier |
| **Repository** | `product-notifier` |
| **Type** | Multi-module microservices |
| **Architecture** | Event-driven (Kafka) |
| **Tier** | P2 |
| **Status** | Development |
| **Owners** | TODO: Add team name |

## Table of Contents

1. [Architecture](#architecture)
2. [Modules](#modules)
3. [Technology Stack](#technology-stack)
4. [Quick Start](#quick-start)
5. [Build & Run](#build--run)
6. [Configuration](#configuration)
7. [Testing](#testing)
8. [Development](#development)
9. [Deployment](#deployment)
10. [Roadmap](#roadmap)
11. [Contributing](#contributing)

---

## Architecture

### System Diagram

```
┌───────────────────────────────────────────────────────────────┐
│                     Product Notifier System                   │
│                                                               │
│  ┌──────────────────────┐       Kafka Topic:                  │
│  │                      │    product-created-events-topic     │
│  │  Product Microservice│                                     │
│  │  ────────────────────│       ┌──────────────────────┐      │
│  │  REST API            │──────▶│   Kafka Broker       │      │
│  │  POST /product       │       │   (localhost:9092)   │      │
│  │  (Create Product)    │       └──────────────────────┘      │
│  │  Publishes Events    │              │                      │
│  └──────────────────────┘              │                      │
│                                        │                      │
│                                        ▼                      │
│  ┌──────────────────────┐       ┌──────────────────────┐      │
│  │                      │       │                      │      │
│  │ Email Notification   │       │ ProductCreatedEvent  │      │
│  │ Microservice         │◀──────│ Handler              │      │
│  │ ─────────────────────│       │ @KafkaListener       │      │
│  │ Event Consumer       │       │                      │      │
│  │ Receives Events      │       │ Consumes Messages    │      │
│  │ (Ready for emails)   │       │                      │      │
│  └──────────────────────┘       └──────────────────────┘      │
│                                                               │
│  ┌──────────────────────┐                                     │
│  │                      │                                     │
│  │ Core Module          │                                     │
│  │ ─────────────────────│                                     │
│  │ Shared Event:        │                                     │
│  │ ProductCreatedEvent  │                                     │
│  │                      │                                     │
│  └──────────────────────┘                                     │
└───────────────────────────────────────────────────────────────┘
```

### Data Flow

```
1. Client sends: POST /product with product details
                         │
                         ▼
2. ProductController validates CreateProductDto
                         │
                         ▼
3. ProductService creates ProductCreatedEvent
                         │
                         ▼
4. Kafka Producer publishes event to Kafka topic
                         │
                         ▼
5. Email Consumer (@KafkaListener) receives event
                         │
                         ▼
6. ProductCreatedEventHandler calls mock-service
   (GET http://localhost:8090/response/200)
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
   SUCCESS       RETRYABLE ERR    NON-RETRYABLE ERR
   (HTTP 200)    (Network,       (HTTP 5xx, other)
                  Connection)
         │               │               │
         └───────────────┼───────────────┘
                         ▼
         RETRY LOGIC (FixedBackOff 3s × 3)
                         │
         ┌───────────────┴───────────────┐
         ▼                               ▼
    SUCCESS              EXHAUSTED RETRIES/NON-RETRYABLE
    (Complete)           → DeadLetterTopic
```

### Module Dependency Tree

```
email-notification-microservice
  ├─ spring-boot-starter-kafka (consumer)
  ├─ spring-boot-starter-webmvc
  ├─ RestTemplate (HTTP calls to mock-service)
  └─ core (ProductCreatedEvent)

product-microservice
  ├─ spring-boot-starter-kafka (producer)
  ├─ spring-boot-starter-webmvc
  └─ core (ProductCreatedEvent)

mock-service (port 8090)
  ├─ spring-boot-starter-web
  └─ [no external dependencies]

core
  ├─ ProductCreatedEvent.java (shared event)
  └─ [no external dependencies beyond Spring]
```

---

## Modules

### 1. core/

**Shared library** containing domain events and DTOs used by all services.

**Contents:**
- `ProductCreatedEvent` - Event fired when product created
  - Fields: `productId`, `title`, `price`, `quantity`
  - Published by: `product-microservice`
  - Consumed by: `email-notification-microservice`

**Build:** `mvn -pl core clean install`

---

### 2. product-microservice/

**Product management service** exposing REST API for product creation.

**Responsibilities:**
- Accept `POST /product` requests with product data
- Validate input via `CreateProductDto`
- Publish `ProductCreatedEvent` to Kafka
- Return product ID to client

**Key Components:**
- `ProductController` - REST endpoint
- `ProductService` - Business logic
- `KafkaConfig` - Producer configuration
- `ProductCreatedEvent` - Event payload (from core)

**Configuration:**
- **Port:** Dynamic (port=0), logged at startup
- **Kafka topic:** `product-created-events-topic`
- **Producer settings:** Idempotent, acks=all, JsonSerializer

**Build:** `mvn -pl product-microservice clean package`

**Run:** `mvn -pl product-microservice spring-boot:run`

---

### 3. email-notification-microservice/

**Event consumer service** that receives product creation events, validates processing via HTTP call to mock-service, and routes failures to Dead Letter Topic based on exception type.

**Responsibilities:**
- Listen to `product-created-events-topic` on Kafka
- Deserialize `ProductCreatedEvent` messages
- Call mock-service HTTP endpoint (`GET /response/200`) to verify processing
- Route retryable errors (network failures) to DLT with exponential backoff (3s × 3 retries)
- Route non-retryable errors (4xx, other) directly to DLT
- Leverage exception-based routing to separate transient vs permanent failures

**Key Components:**
- `EmailNotificationConfig` - Spring configuration
  - Provides `RestTemplate` bean for HTTP calls
- `ProductCreatedEventHandler` - Kafka listener
  - Annotation: `@KafkaListener(topics = "product-created-events-topic")`
  - Method: `handle(ProductCreatedEvent)` - calls mock-service, throws typed exceptions
- `RetryableException` - wraps transient errors (network timeouts, connection refused)
- `NonRetryableException` - wraps permanent errors (server errors, validation failures)
- `KafkaConfig` - configures error handling
  - FixedBackOff: 3-second intervals, 3 retry attempts
  - Registered retryable/non-retryable exception classes
  - DeadLetterPublishingRecoverer routes to DLT after retries exhausted

**Build:** `mvn -pl email-notification-microservice clean package`

**Run:** `mvn -pl email-notification-microservice spring-boot:run`

---

### 4. mock-service/

**Test utility service** that simulates external HTTP endpoints for validating email-notification-microservice error handling and retry behavior.

**Responsibilities:**
- Serve mock HTTP responses for testing different status codes
- Provide endpoints to simulate success, server errors, and timeouts
- Enable testing of retry logic and DLT routing in isolation

**Key Components:**
- `StatusCheckController` - REST controller
  - `GET /response/200` - returns HTTP 200 OK (success)
  - `GET /response/500` - returns HTTP 500 Internal Server Error (server failure)
- `MockServiceApplication` - Spring Boot entry point

**Port:** 8090

**Build:** `mvn -pl mock-service clean package`

**Run:** `mvn -pl mock-service spring-boot:run`

---

## Technology Stack

| Category | Technology |
|----------|-----------|
| **Language** | Java 21 (LTS) |
| **Framework** | Spring Boot 4.0.5 |
| **Build Tool** | Maven 3.8+ |
| **Message Broker** | Apache Kafka 3.x+ |
| **API** | Spring Boot Web MVC |
| **Serialization** | Jackson JSON |
| **Logging** | SLF4J / Logback |

---

## Quick Start

### Prerequisites

- **Java 21** or higher ([Download](https://www.oracle.com/java/technologies/downloads/#java21))
- **Maven 3.8+** (or use included `./mvnw` wrapper)
- **Apache Kafka 3.x+** (required for event messaging)
- **Docker** (optional, for Kafka container)

### Option 1: Local Development (Full Stack)

#### 1. Clone and Navigate

```bash
git clone <repository-url>
cd product-notifier  # Project root directory
```

#### 2. Start Kafka (Required)

```bash
# Using Docker (recommended)
docker run -d --name kafka \
  -p 9092:9092 \
  -e KAFKA_BROKER_ID=1 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  confluentinc/cp-kafka:latest

# Or use local Kafka installation
kafka-server-start.sh config/server.properties
```

#### 3. Build All Modules

```bash
# Full build with tests
mvn clean package

# Quick build (skip tests)
mvn clean package -DskipTests

# Or build specific module
mvn -pl core clean install
mvn -pl product-microservice clean package
mvn -pl email-notification-microservice clean package
```

#### 4. Run Services (in separate terminals)

**Terminal 1 - Product Microservice:**
```bash
mvn -pl product-microservice spring-boot:run
# Output: Tomcat started on port(s): XXXXX (http)
```

**Terminal 2 - Email Notification Microservice:**
```bash
mvn -pl email-notification-microservice spring-boot:run
# Output: Tomcat started on port(s): YYYYY (http)
```

**Terminal 3 - Mock Service (required by email-notification-microservice):**
```bash
mvn -pl mock-service spring-boot:run
# Output: Tomcat started on port(s): 8090 (http)
```

#### 5. Test the Flow

```bash
# Replace XXXXX with actual product-microservice port from step 4
curl -X POST http://localhost:XXXXX/product \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Spring Boot Mug",
    "price": 19.99,
    "quantity": 100
  }'
```

**Expected Response (product ID):**
```json
"550e8400-e29b-41d4-a716-446655440000"
```

**Expected Logs (Terminal 2 - Email Notification Microservice):**
```
Received event: Spring Boot Mug
Received response 200
```

**Expected Logs (Terminal 3 - Mock Service):**
```
GET /response/200 called
```

---

## Build & Run

### Build Entire Project

```bash
# Full build with tests
mvn clean package

# Skip tests (faster iteration)
mvn clean package -DskipTests

# Compile only (no packaging)
mvn clean compile

# Install without running tests
mvn clean install -DskipTests
```

### Build Individual Module

```bash
# Build only core
mvn -pl core clean install

# Build only product-microservice
mvn -pl product-microservice clean package

# Build only email-notification-microservice
mvn -pl email-notification-microservice clean package
```

### Run Individual Service

```bash
# Run product-microservice
mvn -pl product-microservice spring-boot:run

# Run with custom port
mvn -pl product-microservice spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8080"

# Run email-notification-microservice
mvn -pl email-notification-microservice spring-boot:run

# Run with specific profile (e.g., local, dev, prod)
mvn -pl product-microservice spring-boot:run \
  -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

### View Dependency Tree

```bash
# Show dependencies for specific module
mvn -pl product-microservice dependency:tree

# Show full tree including transitive dependencies
mvn dependency:tree
```

---

## Configuration

### Kafka Settings

Both services read Kafka configuration from their respective `application.properties` files.

**Product Microservice (Producer):**
```properties
spring.kafka.producer.bootstrap-servers=localhost:9092,localhost:9094
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
spring.kafka.producer.properties.delivery.timeout.ms=10000
```

**Email Microservice (Consumer):**
- Topic: `product-created-events-topic`
- Group ID: Auto-generated (set in `application.properties` if needed)
- Deserialization: JSON (Jackson)

### Environment Profiles

Create profile-specific configuration:

```bash
# Local development
src/main/resources/application-local.properties

# Development server
src/main/resources/application-dev.properties

# Production
src/main/resources/application-prod.properties
```

Activate profile:
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

### Server Ports

Both services use **dynamic port assignment** (port=0) by default:
- Actual port logged at startup
- Override in `application.properties`: `server.port=8080`

---

## Testing

### Run All Tests

```bash
# All tests in all modules
mvn test

# Skip tests during build
mvn clean package -DskipTests
```

### Run Tests in Specific Module

```bash
# Test only product-microservice
mvn -pl product-microservice test

# Test only email-notification-microservice
mvn -pl email-notification-microservice test
```

### Run Specific Test Class

```bash
mvn test -Dtest=ProductMicroserviceApplicationTests
```

### Generate Coverage Report

```bash
# Requires JaCoCo plugin (add to pom.xml if not present)
mvn test jacoco:report
# Report: target/site/jacoco/index.html
```

### Current Test Structure

- `ProductMicroserviceApplicationTests` - Application startup tests
- `EmailNotificationMicroserviceApplicationTests` - Application startup tests

### Planned Test Enhancements

- [ ] Unit tests for controllers
- [ ] Unit tests for services
- [ ] Integration tests with embedded Kafka
- [ ] Event handler tests
- [ ] Code coverage (JaCoCo)
- [ ] SonarQube integration

---

## Development

### IDE Setup

**IntelliJ IDEA:**
1. Open project root (`product-notifier`)
2. Mark each module as Maven module: Right-click module → Add Framework Support → Maven
3. Run/Debug configurations:
   - Create separate configs for each service
   - Set working directory to project root
   - Set program arguments: `spring-boot:run`

**VS Code:**
1. Install Extension Pack for Java
2. Install Spring Boot Extension Pack
3. Open workspace at project root
4. Run services via Maven tasks or CLI

### Code Style

- **Indentation:** 2 spaces (Spring Boot convention)
- **DTOs:** Use Lombok `@Data` or Java records
- **Events:** Separate event classes for clarity
- **Configuration:** Externalize via `application.properties`
- **Logging:** SLF4J with Logback

### Project Structure

```
product-notifier/  (Project Root)
├── core/
│   ├── src/main/java/dev/toganbayev/core/
│   │   └── ProductCreatedEvent.java
│   ├── src/test/java/
│   └── pom.xml
├── product-microservice/
│   ├── src/main/java/dev/toganbayev/productmicroservice/
│   │   ├── ProductMicroserviceApplication.java
│   │   ├── controller/
│   │   │   ├── ProductController.java
│   │   │   └── ErrorMessage.java
│   │   ├── service/
│   │   │   ├── ProductService.java
│   │   │   ├── ProductServiceImpl.java
│   │   │   ├── dto/
│   │   │   │   └── CreateProductDto.java
│   │   │   └── event/
│   │   │       └── ProductCreatedEvent.java (reference to core)
│   │   └── config/
│   │       └── KafkaConfig.java
│   ├── src/main/resources/
│   │   └── application.properties
│   ├── src/test/java/
│   └── pom.xml
├── email-notification-microservice/
│   ├── src/main/java/dev/toganbayev/emailnotificationmicroservice/
│   │   ├── EmailNotificationMicroserviceApplication.java
│   │   └── handler/
│   │       └── ProductCreatedEventHandler.java
│   ├── src/main/resources/
│   │   └── application.properties
│   ├── src/test/java/
│   └── pom.xml
└── pom.xml (root, if multi-module parent exists)
```

### Common Development Tasks

**Add a new endpoint:**
1. Create controller method
2. Define request/response DTOs
3. Implement service logic
4. Add unit tests
5. Add integration tests (if needed)

**Add a new event:**
1. Add event class to `core/` module
2. Run `mvn -pl core install` to publish
3. Update both microservices to depend on new version
4. Add handler in consumer service

**Debug Kafka Issues:**
```bash
# Check if Kafka is running
docker ps | grep kafka

# View Kafka logs
docker logs kafka

# List topics
kafka-topics.sh --bootstrap-server localhost:9092 --list

# View messages in topic (from beginning)
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic product-created-events-topic --from-beginning
```

---

## Deployment

### Docker

#### Build Images

```bash
# Product Microservice
docker build -t product-microservice:latest product-microservice/

# Email Notification Microservice
docker build -t email-notification-microservice:latest \
  email-notification-microservice/
```

#### Run with Docker Compose (Planned)

```bash
# TODO: Add docker-compose.yml with all services and Kafka
docker-compose up -d
```

#### Manual Docker Run

```bash
# Start Kafka first
docker run -d --name kafka \
  -p 9092:9092 \
  -e KAFKA_BROKER_ID=1 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  confluentinc/cp-kafka:latest

# Product Microservice
docker run -d --name product-microservice \
  -p 8080:8080 \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  --link kafka \
  product-microservice:latest

# Email Notification Microservice
docker run -d --name email-notification-microservice \
  -p 8081:8080 \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  --link kafka \
  email-notification-microservice:latest
```

### Kubernetes (TODO)

- [ ] Create `k8s/namespace.yaml`
- [ ] Create `k8s/kafka-deployment.yaml`
- [ ] Create `k8s/product-microservice-deployment.yaml`
- [ ] Create `k8s/product-microservice-service.yaml`
- [ ] Create `k8s/email-notification-microservice-deployment.yaml`
- [ ] Create `k8s/email-notification-microservice-service.yaml`
- [ ] Add Helm charts for templating

### CI/CD (TODO)

- [ ] GitHub Actions pipeline
- [ ] Build, test, containerize
- [ ] Push to container registry
- [ ] Deploy to staging/production

---

## Roadmap

### Phase 1: Event Foundation [DONE]

- [x] Core module with `ProductCreatedEvent`
- [x] Product microservice with REST API
- [x] Kafka producer configuration
- [x] Email consumer listening to events
- [x] Event-driven architecture foundation

### Phase 2: Product Service Enhancements (Planned)

- [ ] GET /product/{id} - Retrieve product
- [ ] PUT /product/{id} - Update product
- [ ] DELETE /product/{id} - Delete product
- [ ] List all products endpoint
- [ ] Database persistence (PostgreSQL/MySQL)

### Phase 3: Email Service Implementation (Planned)

- [ ] Email template system
- [ ] SMTP configuration
- [ ] Email dispatch for `ProductCreatedEvent`
- [ ] Email status tracking
- [ ] Retry mechanism for failed emails

### Phase 4: Operational Readiness (Planned)

- [ ] Docker Compose for local development
- [ ] Kubernetes manifests
- [ ] Helm charts
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] Prometheus metrics
- [ ] Grafana dashboards
- [ ] Distributed tracing (Jaeger)
- [ ] API documentation (Swagger/OpenAPI)

### Phase 5: Resilience (Planned)

- [ ] Circuit breakers (Resilience4j)
- [ ] API rate limiting
- [ ] Request timeout handling
- [ ] Graceful degradation
- [ ] Health checks and liveness probes

---

## Contributing

Contributions are welcome! Please follow these guidelines:

1. **Create a feature branch** from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Build and test locally:**
   ```bash
   mvn clean package
   mvn test
   ```

3. **Write clear commit messages:**
   ```bash
   git commit -m "Add feature: brief description"
   ```

4. **Push to your branch:**
   ```bash
   git push origin feature/your-feature-name
   ```

5. **Open a Pull Request** with:
   - Clear description of changes
   - List of affected modules
   - Test results

### Development Workflow

- Each service should run independently
- Changes to `core/` require rebuilding both microservices
- Always test the full flow (Product creation → Kafka → Email consumer)
- Use feature branches, never commit directly to `main`

---

## Troubleshooting

### Kafka Connection Failed

**Error:** `org.apache.kafka.common.KafkaException: Failed to construct kafka producer`

**Solution:**
1. Verify Kafka is running:
   ```bash
   docker ps | grep kafka
   ```
2. Check bootstrap servers in `application.properties`:
   ```properties
   spring.kafka.bootstrap-servers=localhost:9092
   ```
3. Ensure both services can reach Kafka (network configuration)

### Spring Context Issues

**Error:** `Failed to load ApplicationContext`

**Solution:**
```bash
# Clean and rebuild
mvn clean package
mvn test
```

### Service Won't Start

**Error:** `Address already in use`

**Solution:**
1. Services use dynamic ports by default (port=0)
2. Check console output for actual assigned port
3. Or override in `application.properties`: `server.port=8080`

### Core Module Not Found

**Error:** `Could not find artifact dev.toganbayev:core`

**Solution:**
1. Build core first:
   ```bash
   mvn -pl core clean install
   ```
2. Then build dependent modules:
   ```bash
   mvn clean package
   ```

### Events Not Being Consumed

**Symptoms:** Messages published to Kafka but handler not triggered

**Checklist:**
1. Verify topic name matches in both services
2. Confirm consumer group is set (auto-generated or explicit)
3. Check consumer service is actually running
4. View Kafka logs: `docker logs kafka`
5. List topic: `kafka-topics.sh --bootstrap-server localhost:9092 --list`
6. View messages: `kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic product-created-events-topic --from-beginning`

---

## License

TODO: Add license information

---

## Acknowledgments

- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework
- [Spring Kafka](https://spring.io/projects/spring-kafka) - Kafka integration
- [Apache Kafka](https://kafka.apache.org/) - Event streaming platform
- [Maven](https://maven.apache.org/) - Build automation
- [Jackson](https://github.com/FasterXML/jackson) - JSON serialization

---

## Resources

### Documentation

- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [Spring Kafka Guide](https://spring.io/projects/spring-kafka)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Maven Documentation](https://maven.apache.org/)
- [Event-Driven Architecture](https://martinfowler.com/articles/201701-event-driven.html)

### Tools & Utilities

- [Kafka Topics UI](https://github.com/Landoop/kafka-topics-ui) - Visual topic management
- [Kafdrop](https://github.com/obsidiandynamics/kafdrop) - Web UI for Kafka
- [kcat](https://github.com/edenhill/kcat) - CLI for Kafka
- [Docker Desktop](https://www.docker.com/products/docker-desktop) - Local container runtime
