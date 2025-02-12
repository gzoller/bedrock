
resource "aws_vpc_endpoint" "secretsmanager" {
    vpc_id              = var.vpc_id
    service_name        = "com.amazonaws.${var.region}.secretsmanager"
    vpc_endpoint_type   = "Interface"
    security_group_ids  = [aws_security_group.secretsmanager_sg.id]
    subnet_ids          = var.private_subnet_ids
    private_dns_enabled = true

    tags = {
      Name = "secretsmanager-endpoint"
    }
}

#
# Create the Secrets
#
resource "aws_secretsmanager_secret" "access_key" {
  name       = "BedrockAccessKey"
  kms_key_id = aws_kms_key.secrets_kms.arn
}

resource "aws_secretsmanager_secret_version" "access_key_version" {
  secret_id     = aws_secretsmanager_secret.access_key.id
  secret_string = jsonencode({
    access_key = "mySecureAccessKey" # initial value
  })
}

resource "aws_secretsmanager_secret" "session_key" {
  name       = "BedrockSessionKey"
  kms_key_id = aws_kms_key.secrets_kms.arn
}

resource "aws_secretsmanager_secret_version" "session_key_version" {
  secret_id     = aws_secretsmanager_secret.session_key.id
  secret_string = jsonencode({
    session_key = "mySecureSessionKey" # initial value
  })
}

resource "aws_security_group" "secretsmanager_sg" {
  vpc_id = var.vpc_id
  name   = "secretsmanager-sg"

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_iam_policy" "secrets_policy" {
    name               = "SecretsManagerVPCOnlyAccess"
    description        = "Allows access to Secrets manager only within a specific vpc"

    policy = jsonencode({
        Version = "2012-10-17"
        Statement = [
            {
                Effect = "Allow"
                Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
                Resource = [
                  "arn:aws:secretsmanager:${var.region}:${var.account_id}:secret:BedrockAccessKey-*",
                  "arn:aws:secretsmanager:${var.region}:${var.account_id}:secret:BedrockSessionKey-*"
                ]
                Condition = {
                    StringEquals = {
                        "aws:SourceVpc" = var.vpc_id
                    }
                }
            }
        ]
    })
}

resource "aws_kms_key" "secrets_kms" {
  description         = "KMS key for Secrets Manager (VPC-only)"
  enable_key_rotation = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { "AWS" = "arn:aws:iam::${var.account_id}:role/my-vpc-role" }
        Action    = "kms:Decrypt"
        Resource  = "*"
        Condition = {
          StringEquals = {
            "aws:SourceVpc" = var.vpc_id
          }
        }
      }
    ]
  })
}

#
# Attach SecretsManager to our vpc role
#
resource "aws_iam_role_policy_attachment" "attach_secrets_policy" {
  role       = var.vpc_role_name
  policy_arn = aws_iam_policy.secrets_policy.arn
}


#
# Secrets Rotation Lambda
#
#
# Create an SNS topic for secret rotation notifications
#
resource "aws_sns_topic" "secrets_rotation" {
  name = "secrets-rotation-topic"
}

#
# Create an IAM Role for the Lambda
#
resource "aws_iam_role" "secrets_rotation_lambda_role" {
  name = "secrets-rotation-lambda-role"

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
# Attach policies to the Lambda role
#
resource "aws_iam_policy" "secrets_rotation_lambda_policy" {
  name        = "SecretsRotationLambdaPolicy"
  description = "Permissions for the secrets rotation Lambda"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret", "secretsmanager:PutSecretValue", "secretsmanager:UpdateSecretVersionStage"]
        Resource = [
          aws_secretsmanager_secret.access_key.arn,
          aws_secretsmanager_secret.session_key.arn
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["sns:Publish"]
        Resource = aws_sns_topic.secrets_rotation.arn
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_sns_publish_attach" {
  role       = aws_iam_role.secrets_rotation_lambda_role.name
  policy_arn = var.sns_publish_policy_arn
}

#
# Deploy the Lambda Function
#
resource "aws_lambda_function" "secrets_rotation" {
  function_name    = "SecretsRotationFunction"
  runtime         = "python3.9"
  handler         = "rotationLambda.lambda_handler"
  role           = aws_iam_role.secrets_rotation_lambda_role.arn
  filename       = "${path.module}/rotationLambda.zip" # Pre-packaged Lambda deployment
  source_code_hash = filebase64sha256("${path.module}/rotationLambda.zip")

  environment {
    variables = {
      SNS_TOPIC_ARN = var.sns_topic_arn
    }
  }
}

#
# Enable Rotation for Secrets
#
resource "aws_secretsmanager_secret_rotation" "access_key_rotation" {
  secret_id           = aws_secretsmanager_secret.access_key.id
  rotation_lambda_arn = aws_lambda_function.secrets_rotation.arn
  rotation_rules {
    automatically_after_days = 10
  }
}

resource "aws_secretsmanager_secret_rotation" "session_key_rotation" {
  secret_id           = aws_secretsmanager_secret.session_key.id
  rotation_lambda_arn = aws_lambda_function.secrets_rotation.arn
  rotation_rules {
    automatically_after_days = 30
  }
}