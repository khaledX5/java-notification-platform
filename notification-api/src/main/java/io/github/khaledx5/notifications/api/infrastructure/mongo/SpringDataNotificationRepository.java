package io.github.khaledx5.notifications.api.infrastructure.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface SpringDataNotificationRepository extends MongoRepository<NotificationDocument, String> {}
