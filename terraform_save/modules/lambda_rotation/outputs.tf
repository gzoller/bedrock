output "lambda_arn" {
  description = "The ARN of the rotation Lambda function"
  value       = aws_lambda_function.secrets_rotation.arn
}