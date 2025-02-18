variable "name" {
  description = "The name prefix for ElastiCache resources"
  type        = string
}

variable "vpc_id" {
  description = "The VPC ID where ElastiCache will be deployed"
  type        = string
}

variable "subnet_ids" {
  description = "List of private subnet IDs for ElastiCache"
  type        = list(string)
}

variable "instance_type" {
  description = "ElastiCache instance type"
  type        = string
  default     = "cache.t3.micro"
}

variable "num_replicas" {
  description = "Number of read replicas"
  type        = number
  default     = 2
}

variable "allowed_cidrs" {
  description = "CIDR blocks allowed to access Redis"
  type        = list(string)
  default     = ["10.0.0.0/16"]  # Allow internal access only
}