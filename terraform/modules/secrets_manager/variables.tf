variable "account_id" {
    type = string
}

variable "region" {
    type = string
}

variable "vpc_id" {
    type = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC"
  type        = string
}

variable "private_subnet_ids" {
    type = list(string)
}

variable "vpc_role_name" {
  description = "IAM Role Name for accessing Secrets Manager"
  type        = string
}

variable "sns_publish_policy_arn" {
  description = "ARN that gives us permission to publish to SNS"
  type        = string
}

variable "sns_topic_arn" {
  description = "ARN for topic to publish onto SNS on rotation"
  type        = string
}