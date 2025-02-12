resource "aws_iam_role" "lambda_secrets_rotation_role" {
  name = var.role_name

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })
}

#
# IAM Policy for SecretsManager Access
#
resource "aws_iam_policy" "lambda_secrets_manager_policy" {
  name        = "${var.role_name}-secrets-manager"
  description = "Allows Lambda to manage secrets in Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
          "secretsmanager:PutSecretValue",
          "secretsmanager:UpdateSecretVersionStage"
        ]
        Resource = var.secrets_arns
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = var.kms_key_arn
      }
    ]
  })
}

#
# IAM Policy for SNS Notifications
#
resource "aws_iam_policy" "lambda_sns_policy" {
  name        = "${var.role_name}-sns"
  description = "Allows Lambda to send messages to SNS"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["sns:Publish"]
        Resource = var.sns_topic_arn
      }
    ]
  })
}

#
# IAM Policy for VPC Access (ENI)
#
resource "aws_iam_policy" "lambda_vpc_access_policy" {
  name        = "${var.role_name}-vpc-access"
  description = "Allows Lambda to create network interfaces for VPC access"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = [
          "ec2:CreateNetworkInterface",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DeleteNetworkInterface"
        ]
        Resource = "*"
      }
    ]
  })
}

#
# IAM Policy for CloudWatch Logging
#
resource "aws_iam_policy" "lambda_logging_policy" {
  name        = "${var.role_name}-logging"
  description = "Allows Lambda to write logs to CloudWatch"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:*:*:*"
      }
    ]
  })
}

#
# Attach Policies to Role
#
resource "aws_iam_role_policy_attachment" "attach_secrets_manager" {
  role       = aws_iam_role.lambda_secrets_rotation_role.name
  policy_arn = aws_iam_policy.lambda_secrets_manager_policy.arn
}

resource "aws_iam_role_policy_attachment" "attach_sns" {
  role       = aws_iam_role.lambda_secrets_rotation_role.name
  policy_arn = aws_iam_policy.lambda_sns_policy.arn
}

resource "aws_iam_role_policy_attachment" "attach_vpc" {
  role       = aws_iam_role.lambda_secrets_rotation_role.name
  policy_arn = aws_iam_policy.lambda_vpc_access_policy.arn
}

resource "aws_iam_role_policy_attachment" "attach_logging" {
  role       = aws_iam_role.lambda_secrets_rotation_role.name
  policy_arn = aws_iam_policy.lambda_logging_policy.arn
}

#
# Output IAM Role ARN
#
output "iam_role_arn" {
  description = "IAM Role ARN for the Lambda function"
  value       = aws_iam_role.lambda_secrets_rotation_role.arn
}