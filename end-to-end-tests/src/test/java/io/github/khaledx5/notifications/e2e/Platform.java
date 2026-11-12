package io.github.khaledx5.notifications.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The whole platform on one Docker network: Kafka, MongoDB, Redis, a fake SMTP server, and the two
 * services running from the jars this build produced.
 *
 * <p>The services are configured only through the environment variables they read in production,
 * and the tests reach them only over HTTP. Nothing of their code is on the test classpath.
 */
final class Platform implements AutoCloseable {

    private static final DockerImageName JAVA_RUNTIME = DockerImageName.parse("eclipse-temurin:21-jre");
    private static final String JAR_IN_CONTAINER = "/application.jar";
    private static final Duration SERVICE_STARTUP = Duration.ofMinutes(3);

    private static final int API_PORT = 8080;
    private static final int WORKER_PORT = 8081;
    private static final int REDIS_PORT = 6379;
    private static final int SMTP_PORT = 1025;
    private static final int MAILPIT_HTTP_PORT = 8025;

    /** Address of the broker for containers on the network; the default listener is for the host. */
    private static final String KAFKA_ON_NETWORK = "kafka:19092";

    private final Network network = Network.newNetwork();

    private final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.2"))
            .withNetwork(network)
            .withNetworkAliases("kafka")
            .withListener(KAFKA_ON_NETWORK);

    /** A single-node replica set, as in compose.yaml: the outbox needs transactions. */
    private final MongoDBContainer mongodb = new MongoDBContainer(DockerImageName.parse("mongo:9.0"))
            .withReplicaSet()
            .withNetwork(network)
            .withNetworkAliases("mongodb");

    private final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8.8-alpine"))
            .withNetwork(network)
            .withNetworkAliases("redis")
            .withExposedPorts(REDIS_PORT);

    private final GenericContainer<?> mailpit = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.33"))
            .withNetwork(network)
            .withNetworkAliases("mailpit")
            .withExposedPorts(SMTP_PORT, MAILPIT_HTTP_PORT);

    private final GenericContainer<?> api = service("e2e.api.jar", API_PORT)
            // The replica set member calls itself localhost; directConnection keeps the driver on this address.
            .withEnv("MONGODB_URI", "mongodb://mongodb:27017/notifications?directConnection=true")
            .withEnv("REDIS_HOST", "redis")
            .withEnv("KAFKA_BOOTSTRAP_SERVERS", KAFKA_ON_NETWORK)
            .dependsOn(kafka, mongodb, redis);

    private final GenericContainer<?> worker = service("e2e.worker.jar", WORKER_PORT)
            .withEnv("REDIS_HOST", "redis")
            .withEnv("KAFKA_BOOTSTRAP_SERVERS", KAFKA_ON_NETWORK)
            .withEnv("SMTP_HOST", "mailpit")
            .withEnv("SMTP_PORT", String.valueOf(SMTP_PORT))
            .dependsOn(kafka, redis, mailpit);

    /** Starts everything, the two services side by side once the infrastructure is up. */
    void start() {
        Startables.deepStart(Stream.of(api, worker)).join();
    }

    String apiUrl() {
        return "http://" + api.getHost() + ":" + api.getMappedPort(API_PORT);
    }

    String workerUrl() {
        return "http://" + worker.getHost() + ":" + worker.getMappedPort(WORKER_PORT);
    }

    String mailpitUrl() {
        return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(MAILPIT_HTTP_PORT);
    }

    String workerLogs() {
        return worker.getLogs();
    }

    @Override
    public void close() {
        Stream.of(api, worker, mailpit, redis, mongodb, kafka).forEach(GenericContainer::stop);
        network.close();
    }

    private GenericContainer<?> service(String jarProperty, int port) {
        return new GenericContainer<>(JAVA_RUNTIME)
                .withNetwork(network)
                .withCopyFileToContainer(MountableFile.forHostPath(jar(jarProperty)), JAR_IN_CONTAINER)
                .withCommand("java", "-jar", JAR_IN_CONTAINER)
                .withExposedPorts(port)
                .waitingFor(Wait.forHttp("/actuator/health/readiness")
                        .forPort(port)
                        .forStatusCode(200)
                        .withStartupTimeout(SERVICE_STARTUP));
    }

    private static Path jar(String property) {
        String location = System.getProperty(property);
        if (location == null || !Files.isRegularFile(Path.of(location))) {
            throw new IllegalStateException("The service jar named by -D" + property + " was not found at " + location
                    + ". Run ./mvnw verify from the repository root, so that the services are packaged first.");
        }
        return Path.of(location);
    }
}
