export TF_VAR_environment="aws"
export TF_VAR_aws_access_key="your-real-access-key"
export TF_VAR_aws_secret_key="your-real-secret-key"

terraform init
terraform apply -var-file=envs/aws/terraform.tfvars -auto-approve