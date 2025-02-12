variable "account_id" {
    type = string
}

variable "region" {
    type = string
}

variable "vpc_id" {
    type = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC"
  type        = string
}

variable "private_subnet_ids" {
    type = list(string)
}

variable "vpc_role_name" {
  description = "IAM Role Name for accessing Secrets Manager"
  type        = string
}

#variable "rotation_lambda_arn" {
#  description = "ARN of the Lambda function for secret rotation"
#  type        = string
#}