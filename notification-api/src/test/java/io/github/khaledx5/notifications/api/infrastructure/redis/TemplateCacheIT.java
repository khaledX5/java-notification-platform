package io.github.khaledx5.notifications.api.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.TestcontainersConfiguration;
import io.github.khaledx5.notifications.api.domain.Template;
import io.github.khaledx5.notifications.api.domain.TemplateRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Templates against a real MongoDB and a real Redis: MongoDB is the source of truth, Redis the cache. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TemplateCacheIT {

    @Autowired
    private TemplateRepository templates;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MongoTemplate mongo;

    @Test
    void theCachingRepositoryIsTheOneTheApplicationUses() {
        assertThat(templates).isInstanceOf(CachedTemplateRepository.class);
    }

    @Test
    void firstReadFillsTheCacheWithAnExpiringEntry() {
        Template template = templates.save(new Template(uniqueKey(), "Welcome", "Hi {{name}}"));
        String cacheKey = CachedTemplateRepository.cacheKey(template.key());
        assertThat(redis.hasKey(cacheKey)).isFalse();

        assertThat(templates.findByKey(template.key())).contains(template);

        assertThat(redis.opsForValue().get(cacheKey)).contains("Hi {{name}}");
        assertThat(redis.getExpire(cacheKey))
                .isBetween(1L, Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void secondReadIsServedFromRedisEvenIfMongoNoLongerHasTheTemplate() {
        Template template = templates.save(new Template(uniqueKey(), null, "Code {{code}}"));
        templates.findByKey(template.key());

        mongo.getCollection("templates").deleteOne(new org.bson.Document("_id", template.key()));

        assertThat(templates.findByKey(template.key())).contains(template);
    }

    @Test
    void savingAgainEvictsTheCachedVersion() {
        String key = uniqueKey();
        templates.save(new Template(key, null, "Old text"));
        templates.findByKey(key);

        Template updated = templates.save(new Template(key, "New subject", "New text"));

        assertThat(redis.hasKey(CachedTemplateRepository.cacheKey(key))).isFalse();
        assertThat(templates.findByKey(key)).contains(updated);
    }

    @Test
    void unknownKeyIsEmptyAndLeavesNoCacheEntry() {
        String key = uniqueKey();

        assertThat(templates.findByKey(key)).isEmpty();
        assertThat(redis.hasKey(CachedTemplateRepository.cacheKey(key))).isFalse();
    }

    private static String uniqueKey() {
        return "tpl-" + UUID.randomUUID();
    }
}
