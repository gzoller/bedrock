resource "aws_secretsmanager_secret" "my_secret" {
  name = "BedrockAccessKey"
}


resource "aws_vpc_endpoint" "secretsmanager" {
    vpc_id             = var.vpc_id
    service_name       = "com.amazonaws.${var.region}.secretsmanager"
    vpc_endpoint_type  = "Interface"
    security_group_ids = [aws_security_group.secretsmanager_sg.id]
    subnet_ids         = module.vpc.private_subnet_ids
}

resource "aws_iam_policy" "secrets_policy" {
    name               = "SecretsManagerVPCOnlyAccess"
    description        = "Allows access to Secrets manager only within a specific vpc"

    policy = jsonencode({
        Version = "2012-10-17"
        Statement = [
            {
                Effect = "Allow"
                Action = "secretsmanager:GetSecretValue"
                Resource = "arn:aws:secretsmanager;${var.region}:${var.account_id}:secret:my-secret-*"
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
}

resource "aws_kms_key_policy" "secrets_kms_policy" {
    key_id = aws_kms_key.secrets_kms.id

    policy = jsonencode({
        Version = "2012-10-17"
        Statement = [
            {
                Effect = "Allow"
                Principal = { AWS = "arn:aws:iam::${var.account_id}:role/my-vpc-role"}
                Action = "kms:Decrypt"
                Resource = "*"
                Condition = {
                    StringEquals = {
                        "aws:SourceVpc" = var.vpc_id
                    }
                }
            }
        ]
    })
}