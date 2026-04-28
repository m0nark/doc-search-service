package com.docsearch.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component("documentSearchHealth")
@RequiredArgsConstructor
public class DocumentSearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient elasticsearchClient;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();

        boolean esHealthy = checkElasticsearch(builder);
        boolean redisHealthy = checkRedis(builder);

        // Service is UP even if Redis is down (degrades gracefully)
        // But ES being down means search is non-functional
        if (esHealthy) {
            builder.up();
            if (!redisHealthy) {
                builder.withDetail("degraded", "Redis unavailable — operating without cache");
            }
        } else {
            builder.down().withDetail("critical", "Elasticsearch unavailable — search is non-functional");
        }

        return builder.build();
    }

    private boolean checkElasticsearch(Health.Builder builder) {
        try {
            var response = elasticsearchClient.cluster().health();
            String status = response.status().jsonValue();
            builder.withDetail("elasticsearch.status", status);
            builder.withDetail("elasticsearch.nodes", response.numberOfNodes());
            return !"red".equals(status);
        } catch (Exception e) {
            log.warn("Elasticsearch health check failed: {}", e.getMessage());
            builder.withDetail("elasticsearch.error", e.getMessage());
            return false;
        }
    }

    private boolean checkRedis(Health.Builder builder) {
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection().ping();
            builder.withDetail("redis.status", "PONG".equals(pong) ? "OK" : pong);
            return true;
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            builder.withDetail("redis.status", "UNAVAILABLE");
            builder.withDetail("redis.error", e.getMessage());
            return false;
        }
    }
}
