#
# 🔹 Create SNS Topics Dynamically
#
resource "aws_sns_topic" "sns_topics" {
  for_each = toset(var.topic_names)
  name     = each.value
}

#
# SNS Publish Role (Allows Publishing to SNS)
#
resource "aws_iam_role" "sns_publish_role" {
  name = "SNSPublishRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = var.assume_publish_role_services
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_policy" "sns_publish_policy" {
  name        = "SNSPublishPolicy"
  description = "Allows publishing to SNS topics"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow",
        Action   = "sns:Publish",
        Resource = [for topic in aws_sns_topic.sns_topics : topic.arn]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "attach_sns_publish_policy" {
  role       = aws_iam_role.sns_publish_role.name
  policy_arn = aws_iam_policy.sns_publish_policy.arn
}


#
# SNS Subscribe Role (Allows Subscribing & Receiving SNS Messages)
#
resource "aws_iam_role" "sns_subscribe_role" {
  name = "SNSSubscribeRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = var.assume_subscribe_role_services
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_policy" "sns_subscribe_policy" {
  name        = "SNSSubscribePolicy"
  description = "Allows subscribing and receiving messages from SNS topics"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow",
        Action   = [
          "sns:Subscribe",
          "sns:Receive"
        ],
        Resource = [for topic in aws_sns_topic.sns_topics : topic.arn]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "attach_sns_subscribe_policy" {
  role       = aws_iam_role.sns_subscribe_role.name
  policy_arn = aws_iam_policy.sns_subscribe_policy.arn
}