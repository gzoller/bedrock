terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

provider "aws" {
  alias  = "aws"
  region = "us-east-1"
}

module "secrets_manager" {
  source      = "../../modules/secrets_manager"
  providers = { aws = aws.aws }
}

module "sns" {
  source      = "../../modules/sns"
  providers = { aws = aws.aws }
}

module "iam" {
  source      = "../../modules/iam"
  providers = { aws = aws.aws }
  sns_topic_arn = module.sns.secret_rotation_arn
}

module "lambda" {
  source      = "../../modules/lambda"
  providers = { aws = aws.aws }
  lambda_role_arn = module.iam.lambda_role_arn
  sns_topic_arn = module.sns.secret_rotation_arn
}

resource "aws_secretsmanager_secret_rotation" "rotation" {
  provider             = aws.aws
  secret_id           = module.secrets_manager.my_secret_id
  rotation_lambda_arn = module.lambda.rotation_lambda_arn

  rotation_rules {
    automatically_after_days = 30
  }
}