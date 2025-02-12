
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
# Set up secret rotation
#
#resource "aws_secretsmanager_secret_rotation" "bedrock_access_key_rotation" {
#  secret_id           = aws_secretsmanager_secret.bedrock_access_key.id
#  rotation_lambda_arn = var.rotation_lambda_arn

#  rotation_rules {
#    automatically_after_days = 10
#  }
#}

#resource "aws_secretsmanager_secret_rotation" "bedrock_session_key_rotation" {
#  secret_id           = aws_secretsmanager_secret.bedrock_session_key.id
#  rotation_lambda_arn = var.rotation_lambda_arn

#  rotation_rules {
#    automatically_after_days = 30
#  }
#}

#
# Attach SecretsManager to our vpc role
#
resource "aws_iam_role_policy_attachment" "attach_secrets_policy" {
  role       = var.vpc_role_name
  policy_arn = aws_iam_policy.secrets_policy.arn
}