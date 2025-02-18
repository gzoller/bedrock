output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.this.id
}

output "private_subnet_ids" {
  description = "List of private subnet IDs"
  value       = aws_subnet.private[*].id
}

output "public_subnet_ids" {
  description = "List of public subnet IDs"
  value       = aws_subnet.public[*].id
}

output "vpc_cidr" {
  description = "CIDR block for the VPC"
  value       = var.vpc_cidr
}

output "vpc_role_name" {
  description = "IAM Role Name for VPC"
  value       = aws_iam_role.vpc_role.name
}

output "private_route_table_id" {
  description = "Route table ID for private subnets"
  value       = aws_route_table.private.id
}