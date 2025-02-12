module "infra" {
  source      = "./envs/${var.environment}"
  aws_provider = var.environment == "aws" ? aws.aws : aws.localstack
}

module "secrets_manager" {
  source = "./modules/secrets_manager"
  providers = { aws = aws.localstack }
}

module "sns" {
  source = "./modules/sns"
  providers = { aws = aws.localstack }
}

module "iam" {
  source = "./modules/iam"
  providers = { aws = aws.localstack }
  sns_topic_arn = module.sns.secret_rotation_arn
}

module "lambda" {
  source = "./modules/lambda"
  providers = { aws = aws.localstack }
  lambda_role_arn = module.iam.lambda_role_arn
  sns_topic_arn = module.sns.secret_rotation_arn
}