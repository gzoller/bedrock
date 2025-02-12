terraform {
  cloud {
    organization = "Endpoint_Store"
    workspaces {
      name = "Bedrock"
    }
  }
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.16"
    }
  }

  required_version = ">= 1.2.0"
}

provider "aws" {
  region = "us-east-1"
}

resource "aws_instance" "app_server" {
  ami           = "ami-04681163a08179f28"
  instance_type = "t2.micro"

  tags = {
    Name = var.instance_name
  }
}

