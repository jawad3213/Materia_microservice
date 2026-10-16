# ===================================================================
# Fargate test environment - outputs.tf (read by up.sh)
# ===================================================================

output "app_url" {
  description = "The application"
  value       = local.app_url
}

output "name" {
  description = "The prefix of every resource; the repositories are <registry>/<name>-<image>"
  value       = local.name
}

output "region" {
  value = var.aws_region
}

output "registry" {
  description = "The ECR registry the images are pushed to"
  value       = module.ecr.registry
}

output "repository_urls" {
  description = "One ECR repository per image"
  value       = module.ecr.repository_urls
}

output "cluster" {
  value = aws_ecs_cluster.this.name
}

output "subnets" {
  description = "Where up.sh runs the one-off database users task"
  value       = join(",", module.network.public_subnet_ids)
}

output "tasks_security_group" {
  value = aws_security_group.tasks.id
}

output "db_roles_task_definition" {
  value = aws_ecs_task_definition.db_roles.arn
}

output "frontend_target_group" {
  value = aws_lb_target_group.frontend.arn
}

output "attachments_bucket" {
  value = aws_s3_bucket.attachments.bucket
}

output "log_group" {
  description = "The containers' logs: aws logs tail <log_group> --follow --log-stream-name-prefix <service>"
  value       = aws_cloudwatch_log_group.app.name
}

output "demo_password" {
  description = "Password of the demo accounts admin@, purchaser@ and receiver@materia.com (spring_profile dev)"
  value       = random_password.seed.result
  sensitive   = true
}
