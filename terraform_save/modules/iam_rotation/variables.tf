variable "role_name" {
  description = "Name of the IAM role"
  type        = string
}

variable "secrets_arns" {
  description = "List of SecretsManager ARNs the Lambda function can manage"
  type        = list(string)
}

variable "kms_key_arn" {
  description = "KMS key ARN used to encrypt secrets"
  type        = string
}

variable "sns_topic_arn" {
  description = "ARN of the SNS topic to send notifications"
  type        = string
}