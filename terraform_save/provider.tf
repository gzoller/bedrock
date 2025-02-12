terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  alias  = "localstack"
  region = "us-east-1"
  access_key = var.aws_access_key
  secret_key = var.aws_secret_key
  skip_credentials_validation = true
  skip_metadata_api_check     = true
}