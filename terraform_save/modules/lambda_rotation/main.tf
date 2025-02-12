resource "aws_lambda_function" "secrets_rotation" {
  function_name = "SecretsRotationLambda"
  role          = var.lambda_role_arn
  handler       = "rotation_lambda.lambda_handler"
  runtime       = "python3.9"

  filename         = "${path.module}/rotationLambda.zip"
  source_code_hash = filebase64sha256("${path.module}/rotationLambda.zip")

  environment {
    variables = {
      SECRETS_TO_ROTATE = jsonencode(var.secrets_to_rotate)
      SNS_TOPIC_ARN     = var.sns_topic_arn
    }
  }

  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [var.security_group_id]
  }
}


resource "aws_iam_role" "lambda_secrets_rotation_role" {
  name = "LambdaSecretsRotationRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { "Service": "lambda.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_policy" "lambda_secrets_rotation_policy" {
  name        = "LambdaSecretsRotationPolicy"
  description = "IAM policy for Lambda to rotate secrets"

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
        Effect = "Allow"
        Action = [
          "kms:Decrypt"
        ]
        Resource = var.kms_key_arn
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:*:*:*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "attach_lambda_secrets_rotation" {
  role       = aws_iam_role.lambda_secrets_rotation_role.name
  policy_arn = aws_iam_policy.lambda_secrets_rotation_policy.arn
}

#
# Establish log groups for clear SecretsManager rotation logging
#
resource "aws_cloudwatch_log_group" "secrets_rotation_logs" {
  name              = "/aws/lambda/${var.lambda_name}"
  retention_in_days = 30
}

resource "aws_iam_policy" "lambda_logging" {
  name        = "LambdaLoggingPolicy"
  description = "Allows Lambda to write logs to CloudWatch"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ]
      Resource = "arn:aws:logs:*:*:*"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "attach_lambda_logging" {
  role       = aws_iam_role.lambda_secrets_rotation_role.name
  policy_arn = aws_iam_policy.lambda_logging.arn
}