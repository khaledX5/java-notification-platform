package io.github.khaledx5.notifications.api.infrastructure.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface SpringDataTemplateRepository extends MongoRepository<TemplateDocument, String> {}
