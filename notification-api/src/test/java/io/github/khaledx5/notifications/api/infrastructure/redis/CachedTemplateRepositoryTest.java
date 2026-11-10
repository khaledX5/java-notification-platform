package io.github.khaledx5.notifications.api.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import io.github.khaledx5.notifications.api.domain.Template;
import io.github.khaledx5.notifications.api.domain.TemplateRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/** Behaviour of the cache decorator when Redis misbehaves; the happy path runs against a real Redis. */
@ExtendWith(MockitoExtension.class)
class CachedTemplateRepositoryTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Template LOGIN_CODE = new Template("login-code", null, "Code {{code}}");
    private static final String CACHE_KEY = CachedTemplateRepository.cacheKey("login-code");

    @Mock
    private TemplateRepository database;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private CachedTemplateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CachedTemplateRepository(
                database, redis, JsonMapper.builder().build(), new TemplateCacheProperties(TTL));
    }

    @Test
    void readFallsBackToTheDatabaseWhenRedisIsDown() {
        given(redis.opsForValue()).willThrow(new RedisConnectionFailureException("connection refused"));
        given(database.findByKey("login-code")).willReturn(Optional.of(LOGIN_CODE));

        assertThat(repository.findByKey("login-code")).contains(LOGIN_CODE);
    }

    @Test
    void unreadableCacheEntryIsTreatedAsAMissAndOverwritten() {
        given(redis.opsForValue()).willReturn(values);
        given(values.get(CACHE_KEY)).willReturn("not json");
        given(database.findByKey("login-code")).willReturn(Optional.of(LOGIN_CODE));

        assertThat(repository.findByKey("login-code")).contains(LOGIN_CODE);

        then(values).should().set(eq(CACHE_KEY), contains("Code {{code}}"), eq(TTL));
    }

    @Test
    void cacheEntryThatIsNoLongerAValidTemplateIsTreatedAsAMiss() {
        given(redis.opsForValue()).willReturn(values);
        given(values.get(CACHE_KEY)).willReturn("{\"key\":\"login-code\",\"body\":\"\"}");
        given(database.findByKey("login-code")).willReturn(Optional.of(LOGIN_CODE));

        assertThat(repository.findByKey("login-code")).contains(LOGIN_CODE);
    }

    @Test
    void cachedTemplateIsServedWithoutTouchingTheDatabase() {
        given(redis.opsForValue()).willReturn(values);
        given(values.get(CACHE_KEY)).willReturn("{\"key\":\"login-code\",\"body\":\"Code {{code}}\"}");

        assertThat(repository.findByKey("login-code")).contains(LOGIN_CODE);

        then(database).should(never()).findByKey(anyString());
    }

    @Test
    void unknownTemplateIsNotCached() {
        given(redis.opsForValue()).willReturn(values);
        given(database.findByKey("login-code")).willReturn(Optional.empty());

        assertThat(repository.findByKey("login-code")).isEmpty();

        then(values).should(never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void saveStillSucceedsWhenTheCacheCannotBeEvicted() {
        given(database.save(LOGIN_CODE)).willReturn(LOGIN_CODE);
        willThrow(new RedisConnectionFailureException("connection refused"))
                .given(redis)
                .delete(CACHE_KEY);

        assertThat(repository.save(LOGIN_CODE)).isEqualTo(LOGIN_CODE);
    }

    @Test
    void saveWritesToTheDatabaseBeforeEvictingTheCache() {
        given(database.save(LOGIN_CODE)).willReturn(LOGIN_CODE);

        repository.save(LOGIN_CODE);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(database, redis);
        order.verify(database).save(LOGIN_CODE);
        order.verify(redis).delete(CACHE_KEY);
    }
}
