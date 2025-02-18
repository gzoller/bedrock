# -------------------------------
# Create an ElastiCache Subnet Group
# -------------------------------
resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis-subnet-group"
  subnet_ids = var.subnet_ids
}

# -------------------------------
# Security Group for Redis
# -------------------------------
resource "aws_security_group" "redis" {
  name        = "${var.name}-redis-sg"
  description = "Security group for Redis"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidrs  # Restrict access (e.g., only within VPC)
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.name}-redis-sg"
  }
}

# -------------------------------
# Create the ElastiCache Redis Cluster
# -------------------------------
resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = "${var.name}-redis"
  description                = "Multi-AZ ElastiCache Redis for ${var.name}"
  engine                     = "redis"
  engine_version             = "7.0"
  node_type                  = var.instance_type
  num_cache_clusters         = var.num_replicas + 1  # Primary + Replicas
  parameter_group_name       = "default.redis7"
  port                       = 6379
  automatic_failover_enabled = true  # Enables Multi-AZ failover
  multi_az_enabled           = true
  security_group_ids         = [aws_security_group.redis.id]
  subnet_group_name          = aws_elasticache_subnet_group.this.name

  tags = {
    Name = "${var.name}-redis"
  }
}

# -------------------------------
# ElastiCache Parameter Group (Custom Redis Config)
# -------------------------------
resource "aws_elasticache_parameter_group" "redis" {
  name   = "${var.name}-redis-params"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"  # Eviction policy (Least Recently Used)
  }

  tags = {
    Name = "${var.name}-redis-params"
  }
}