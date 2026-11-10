package io.github.khaledx5.notifications.api.infrastructure.redis;

import io.github.khaledx5.notifications.api.domain.Template;
import io.github.khaledx5.notifications.api.domain.TemplateRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-aside decorator around the MongoDB template repository.
 *
 * <p>Reads try Redis first and fall back to MongoDB, then fill the cache. Writes go to MongoDB and
 * evict the cached entry. The cache is an optimisation only: if Redis is down or holds an entry that
 * cannot be read, the request is served from MongoDB and a warning is logged.
 */
@Primary
@Repository
@EnableConfigurationProperties(TemplateCacheProperties.class)
class CachedTemplateRepository implements TemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(CachedTemplateRepository.class);

    static final String KEY_PREFIX = "notification-api:template:";

    private final TemplateRepository delegate;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final TemplateCacheProperties properties;

    CachedTemplateRepository(
            @Qualifier("mongoTemplateRepository") TemplateRepository delegate,
            StringRedisTemplate redis,
            JsonMapper json,
            TemplateCacheProperties properties) {
        this.delegate = delegate;
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public Template save(Template template) {
        Template saved = delegate.save(template);
        evict(saved.key());
        return saved;
    }

    @Override
    public Optional<Template> findByKey(String key) {
        Optional<Template> cached = readFromCache(key);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Template> stored = delegate.findByKey(key);
        stored.ifPresent(this::writeToCache);
        return stored;
    }

    private Optional<Template> readFromCache(String key) {
        try {
            String cached = redis.opsForValue().get(cacheKey(key));
            if (cached == null) {
                return Optional.empty();
            }
            CachedTemplate entry = json.readValue(cached, CachedTemplate.class);
            return Optional.of(new Template(entry.key(), entry.subject(), entry.body()));
        } catch (DataAccessException | JacksonException | IllegalArgumentException ex) {
            log.warn("Template cache read failed for key '{}', falling back to the database", key, ex);
            return Optional.empty();
        }
    }

    private void writeToCache(Template template) {
        try {
            String value =
                    json.writeValueAsString(new CachedTemplate(template.key(), template.subject(), template.body()));
            redis.opsForValue().set(cacheKey(template.key()), value, properties.cacheTtl());
        } catch (DataAccessException | JacksonException ex) {
            log.warn("Template cache write failed for key '{}'", template.key(), ex);
        }
    }

    private void evict(String key) {
        try {
            redis.delete(cacheKey(key));
        } catch (DataAccessException ex) {
            log.warn(
                    "Template cache eviction failed for key '{}'; a stale entry may be served for up to {}",
                    key,
                    properties.cacheTtl(),
                    ex);
        }
    }

    static String cacheKey(String templateKey) {
        return KEY_PREFIX + templateKey;
    }

    /** JSON shape of a cache entry, kept separate from the domain type. */
    record CachedTemplate(String key, String subject, String body) {}
}
