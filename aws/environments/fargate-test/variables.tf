# ===================================================================
# Fargate test environment - variables.tf
# ===================================================================

variable "project" {
  description = "Project name, the prefix of every resource"
  type        = string
  default     = "materia"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "fargate-test"
}

variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "eu-west-3"
}

variable "allowed_cidrs" {
  description = <<-EOT
    Who may open the application (the load balancer's port 80), e.g. ["203.0.113.7/32"] for your own address
    (curl -s https://checkip.amazonaws.com). No default: the demo accounts would otherwise be open to anyone.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.allowed_cidrs) > 0
    error_message = "List at least one CIDR block."
  }
}

variable "image_tag" {
  description = "Tag of the images to run (up.sh pushes them tagged with the git commit)"
  type        = string
}

variable "spring_profile" {
  description = "Spring profile: dev seeds the demo accounts and data, prod starts empty"
  type        = string
  default     = "dev"
}

variable "vpc_cidr" {
  description = "VPC address range"
  type        = string
  default     = "10.40.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "Public subnets, in two availability zones (the load balancer needs two)"
  type        = list(string)
  default     = ["10.40.1.0/24", "10.40.2.0/24"]
}

variable "log_retention_days" {
  description = "How long CloudWatch keeps the containers' logs"
  type        = number
  default     = 3
}

variable "api_rate_limit_per_minute" {
  description = "Requests a minute per client at the gateway"
  type        = number
  default     = 300
}
