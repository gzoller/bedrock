resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags = {
    Name = var.vpc_name
  }
}

# Create an Internet Gateway for Public Subnets
resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.vpc_name}-igw"
  }
}

# Create Elastic IP for NAT Gateway (allows private subnets to reach the internet)
resource "aws_eip" "nat" {
  tags = {
    Name = "bedrock-nat-gateway"
  }
}

# Create a NAT Gateway for Private Subnets
resource "aws_nat_gateway" "this" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id  # Attach to first public subnet
}

# Public Subnets (Multi-AZ)
resource "aws_subnet" "public" {
  count = length(var.public_subnet_cidrs)

  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  map_public_ip_on_launch = true
  availability_zone       = element(var.availability_zones, count.index)

  tags = {
    Name                             = "public-subnet-${count.index}"
    "kubernetes.io/role/elb"         = "1"   # Tagging for Load Balancer
  }
}

# Private Subnets (Multi-AZ, for EKS & ElastiCache)
resource "aws_subnet" "private" {
  count = length(var.private_subnet_cidrs)

  vpc_id            = aws_vpc.this.id
  cidr_block        = var.private_subnet_cidrs[count.index]
  availability_zone = element(var.availability_zones, count.index)

  tags = {
    Name                                     = "private-subnet-${count.index}"
    "kubernetes.io/role/internal-elb"        = "1"
    "kubernetes.io/cluster/bedrock-cluster"  = "shared"
    "aws:elasticache:subnet-group"           = "true"   # Tag for Redis Subnet Group
  }
}

# Public Route Table
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
}

resource "aws_route_table_association" "public_assoc" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Private Route Table (For Private Subnets, with NAT Gateway)
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.this.id
  }

  tags = {
    Name = "${var.vpc_name}-private-route-table"
  }
}

resource "aws_route_table_association" "private_assoc" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# IAM Role for Resources
resource "aws_iam_role" "vpc_role" {
  name = "${var.vpc_name}-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      for service in var.trusted_services : {
        Effect    = "Allow"
        Principal = { "Service": service }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}