output "sns_topic_arns" {
  description = "Map of SNS topic ARNs"
  value       = { for topic, sns in aws_sns_topic.sns_topics : topic => sns.arn }
}

output "sns_publish_role_arn" {
  description = "IAM Role ARN for publishing to SNS"
  value       = aws_iam_role.sns_publish_role.arn
}

output "sns_subscribe_role_arn" {
  description = "IAM Role ARN for subscribing to SNS"
  value       = aws_iam_role.sns_subscribe_role.arn
}

output "sns_publish_policy_arn" {
  description = "IAM Policy ARN for publishing to SNS"
  value       = aws_iam_policy.sns_publish_policy.arn
}

output "sns_subscribe_policy_arn" {
  description = "IAM Policy ARN for subscribing to SNS"
  value       = aws_iam_policy.sns_subscribe_policy.arn
}