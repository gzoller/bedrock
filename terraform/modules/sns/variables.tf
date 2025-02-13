variable "topic_names" {
  description = "List of SNS topic names to create"
  type        = list(string)
}

variable "assume_publish_role_services" {
  description = "List of AWS services that can assume this IAM role for publish"
  type        = list(string)
}

variable "assume_subscribe_role_services" {
  description = "List of AWS services that can assume this IAM role for subscribe"
  type        = list(string)
}