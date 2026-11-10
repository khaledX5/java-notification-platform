package io.github.khaledx5.notifications.api.infrastructure.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.TestcontainersConfiguration;
import io.github.khaledx5.notifications.api.domain.Template;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;

@DataMongoTest
@Import({TestcontainersConfiguration.class, MongoTemplateRepository.class})
class MongoTemplateRepositoryTests {

    @Autowired
    private MongoTemplateRepository repository;

    @Autowired
    private SpringDataTemplateRepository documents;

    @Test
    void storedTemplateComesBackUnchanged() {
        Template template = new Template(uniqueKey(), "Order {{orderId}} shipped", "Hi {{name}}");

        repository.save(template);

        assertThat(repository.findByKey(template.key())).contains(template);
    }

    @Test
    void templateWithoutSubjectComesBackWithoutOne() {
        Template template = new Template(uniqueKey(), null, "Code {{code}}");

        repository.save(template);

        assertThat(repository.findByKey(template.key())).contains(template);
    }

    @Test
    void savingTheSameKeyReplacesTheTemplate() {
        String key = uniqueKey();
        repository.save(new Template(key, null, "Old text"));
        long countAfterFirstSave = documents.count();

        Template updated = repository.save(new Template(key, "New subject", "New text"));

        assertThat(documents.count()).isEqualTo(countAfterFirstSave);
        assertThat(repository.findByKey(key)).contains(updated);
    }

    @Test
    void unknownKeyIsEmpty() {
        assertThat(repository.findByKey(uniqueKey())).isEmpty();
    }

    private static String uniqueKey() {
        return "tpl-" + UUID.randomUUID();
    }
}
