# Auto-fetch AWS account ID
data "aws_caller_identity" "current" {}

# Auto-fetch VPC ID based on tag
data "aws_vpc" "selected" {
    filter {
        name   = "tag:Name"
        values = ["my-vpc"]
    }
}

variable "vpc_id" {
    default = data.aws_vpc.selected.id
}

variable "region" {
    default = "us-east-1"
}

variable "account_id" {
    default = data.aws_caller_identity.current.account_id
}
