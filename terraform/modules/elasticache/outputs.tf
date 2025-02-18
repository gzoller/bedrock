output "redis_endpoint" {
  description = "Primary endpoint for the Redis cluster"
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "redis_security_group_id" {
  description = "Security group ID for Redis"
  value       = aws_security_group.redis.id
}