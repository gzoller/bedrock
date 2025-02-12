variable "lambda_role_arn" {
  description = "IAM role ARN for the Lambda function"
  type        = string
}

variable "secrets_to_rotate" {
  description = "List of secrets the Lambda function will rotate"
  type        = list(string)
}

variable "sns_topic_arn" {
  description = "SNS topic ARN for notifications"
  type        = string
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for Lambda networking"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID for Lambda"
  type        = string
}

variable "secrets_arns" {
  description = "List of SecretsManager ARNs that Lambda can rotate"
  type        = list(string)
}

variable "kms_key_arn" {
  description = "KMS key ARN for Secrets encryption"
  type        = string
}

variable "lambda_name" {
  description = "Name of the Lambda function"
  type        = string
}