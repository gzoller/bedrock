module "secrets_manager" {
  source = "../../modules/secrets_manager"
  providers = { aws = aws.localstack }
}

module "sns" {
  source = "../../modules/sns"
  providers = { aws = aws.localstack }
}

module "iam" {
  source = "../../modules/iam"
  providers = { aws = aws.localstack }
}

module "lambda" {
  source = "../../modules/lambda"
  providers = { aws = aws.localstack }
}

resource "aws_secretsmanager_secret_rotation" "rotation" {
  provider = aws.localstack
  secret_id           = module.secrets_manager.my_secret_id
  rotation_lambda_arn = module.lambda.rotation_lambda_arn

  rotation_rules {
    automatically_after_days = 30
  }
}