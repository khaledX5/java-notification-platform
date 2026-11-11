package io.github.khaledx5.notifications.api.infrastructure.mongo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Turns on MongoDB multi-document transactions for {@code @Transactional} methods.
 *
 * <p>Spring Boot does not register a transaction manager for MongoDB on its own, because
 * transactions need a replica set. The MongoDB in compose.yaml runs as a single-node replica set for
 * exactly this reason.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
class MongoTransactionConfiguration {

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }
}
