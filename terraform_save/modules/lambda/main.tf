resource "aws_lambda_function" "rotation_lambda" {
  function_name = "RotateSecretFunction"
  role          = var.lambda_role_arn
  handler       = "rotationLambda.lambda_handler"
  runtime       = "python3.9"

  filename         = "/scripts/rotationLambda.zip"
  source_code_hash = filebase64sha256("/scripts/rotationLambda.zip")
}