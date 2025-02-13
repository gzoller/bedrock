module "vpc" {
  source               = "../../modules/vpc"
  vpc_cidr             = "10.0.0.0/16"
  vpc_name             = "bedrock-vpc"
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.3.0/24", "10.0.4.0/24"]
  availability_zones   = ["us-east-1a", "us-east-1b"]
  trusted_services     = ["ec2.amazonaws.com", "lambda.amazonaws.com"]
}

module "sns" {
    source             = "../../modules/sns"
    topic_names        = ["SecretKeyRotation"]
    assume_publish_role_services   = ["lambda.amazonaws.com"]
    assume_subscribe_role_services = ["ec2.amazonaws.com"]

}

module "secrets_manager" {
    source                   = "../../modules/secrets_manager"
    region                   = var.region
    account_id               = var.account_id
    vpc_id                   = module.vpc.vpc_id
    vpc_cidr                 = module.vpc.vpc_cidr
    private_subnet_ids       = module.vpc.private_subnet_ids
    vpc_role_name            = module.vpc.vpc_role_name
    sns_publish_policy_arn   = module.sns.sns_publish_policy_arn
    sns_topic_arn            = lookup(module.sns.sns_topic_arns, "SecretKeyRotation", null)
}

output "private_subnet_ids" {
  value = module.vpc.private_subnet_ids
}
