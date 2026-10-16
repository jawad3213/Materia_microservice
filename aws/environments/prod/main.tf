# ===================================================================
# Environment root - main.tf (identical in staging and prod; the
# differences live in terraform.tfvars and the backend key)
#
#   GitHub Actions --OIDC--> deploy role --ECR push--> images
#                                       \--SSM Run Command--> EC2
#   EC2 (docker compose: frontend nginx :80 -> backend -> postgres)
#     reads its .env from Parameter Store /<project>/<env>/app/*
# ===================================================================

data "aws_caller_identity" "current" {}

locals {
  name = "${var.project}-${var.environment}"

  # Repositories whose images this environment promotes (prod: staging's), same account and region
  promotion_source_repository_arns = var.promote_from_environment == "" ? [] : [
    for service in ["backend", "identity", "masterdata", "finance", "analytics", "gateway", "frontend"] :
    "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/${var.project}-${var.promote_from_environment}-${service}"
  ]

  tags = {
    Project     = var.project
    Environment = var.environment
  }

  app_url = var.app_url != "" ? trimsuffix(var.app_url, "/") : "http://${module.ec2.public_ip}"

  # Settings the backend needs and that have no default in application.properties.
  # Anything in var.app_config overrides these.
  default_app_config = {
    SPRING_PROFILES_ACTIVE              = var.spring_profile
    POSTGRES_DB                         = "materia"
    POSTGRES_USER                       = "materia"
    SPRING_DATASOURCE_DRIVER_CLASS_NAME = "org.postgresql.Driver"
    SPRING_JPA_HIBERNATE_DIALECT        = "org.hibernate.dialect.PostgreSQLDialect"
    SPRING_FLYWAY_DEFAULT_SCHEMA        = "public"
    SPRING_JPA_BATCH_SIZE               = "50"
    SPRING_JPA_ORDER_INSERTS            = "true"
    SPRING_JPA_ORDER_UPDATES            = "true"
    APP_MESSAGING_TYPE                  = "kafka"
    SPRING_KAFKA_BOOTSTRAP_SERVERS      = "kafka:29092"
    # No SPRING_KAFKA_CONSUMER_GROUP_ID: every service reads this .env, and one group for all would split the events
    # between them (each event reaching one service only). Each service has its own (app.events.consumer-group).
    SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET = "earliest"
    SPRING_KAFKA_LISTENER_AUTO_STARTUP      = "true"
    JWT_EXPIRATION_MS                       = "900000"
    SPRINGDOC_API_DOCS_PATH                 = "/v3/api-docs"
    SPRINGDOC_SWAGGER_UI_PATH               = "/swagger-ui.html"
    SPRINGDOC_INFO_TITLE                    = "Materia API"
    SPRINGDOC_INFO_DESCRIPTION              = "Materia procure-to-pay API (${var.environment})"
    SPRINGDOC_INFO_VERSION                  = "1.0.0"
    SPRINGDOC_INFO_CONTACT_NAME             = "Materia"
    SPRINGDOC_INFO_CONTACT_EMAIL            = var.contact_email
    APP_CORS_ALLOWED_ORIGINS                = local.app_url
    APP_LOGIN_URL                           = "${local.app_url}/login"
    APP_RESET_PASSWORD_URL                  = "${local.app_url}/reset-password"
    APP_AUTH_REFRESH_COOKIE_SECURE          = tostring(var.refresh_cookie_secure)
    JAVA_TOOL_OPTIONS                       = "-XX:MaxRAMPercentage=60"
    GATEWAY_RATE_LIMIT_MAX_RPM              = tostring(var.api_rate_limit_per_minute)
  }
}

# -------------------------------------------------------------------
# Network and firewall
# -------------------------------------------------------------------
module "network" {
  source = "../../modules/network"

  name                = local.name
  vpc_cidr            = var.vpc_cidr
  public_subnet_cidrs = var.public_subnet_cidrs
  tags                = local.tags
}

module "security" {
  source = "../../modules/security"

  name               = local.name
  vpc_id             = module.network.vpc_id
  allowed_http_cidrs = var.allowed_http_cidrs
  enable_https       = var.enable_https
  tags               = local.tags
}

# -------------------------------------------------------------------
# Images
# -------------------------------------------------------------------
module "ecr" {
  source = "../../modules/ecr"

  name         = local.name
  keep_images  = var.ecr_keep_images
  force_delete = var.ecr_force_delete
  tags         = local.tags
}

# -------------------------------------------------------------------
# Application configuration and secrets (Parameter Store)
# -------------------------------------------------------------------
resource "random_password" "postgres" {
  length  = 32
  special = false # the value goes through a docker compose .env file
}

# RS256 key pair that signs and verifies access tokens. The backend reads each as the Base64 of its PEM, so it
# stays on one line in the docker compose .env file.
resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

# The secret procurement presents to identity for its service tokens (identity.v1.ServiceTokens): procurement
# sends it as SERVICE_CLIENT_SECRET, identity checks it against SERVICE_CLIENT_PROCUREMENT_SECRET.
resource "random_password" "service_client_procurement" {
  length  = 48
  special = false # the value goes through a docker compose .env file
}

# Named after the monolith before procurement-service: the same secret, not a new one.
moved {
  from = random_password.service_client_monolith
  to   = random_password.service_client_procurement
}

# The secret master data presents to identity for its service tokens.
resource "random_password" "service_client_masterdata" {
  length  = 48
  special = false # the value goes through a docker compose .env file
}

# The secret finance presents to identity for its service tokens.
resource "random_password" "service_client_finance" {
  length  = 48
  special = false # the value goes through a docker compose .env file
}

# The secret analytics presents to identity for its service tokens.
resource "random_password" "service_client_analytics" {
  length  = 48
  special = false # the value goes through a docker compose .env file
}

# Each service's database user (aws/deploy/db/service-roles.sql): it owns its schema and nothing else.
resource "random_password" "db_service" {
  for_each = toset(["identity", "masterdata", "procurement", "finance", "analytics"])
  length   = 32
  special  = false # the value goes through a docker compose .env file
}

# Grafana's admin account (aws/deploy/docker-compose.yml); Grafana is reached through an SSM port forward only.
resource "random_password" "grafana_admin" {
  length  = 32
  special = false
}

# First administrator, created by the backend while the users table is empty (AdminAccountBootstrap) and
# asked for a new password at the first sign-in. The special characters are the policy's that survive a
# docker compose .env file unquoted.
resource "random_password" "bootstrap_admin" {
  length           = 24
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
  min_special      = 2
  override_special = "!@%^*.,?"
}

locals {
  bootstrap_admin = var.bootstrap_admin_email != "" ? {
    config  = { APP_BOOTSTRAP_ADMIN_EMAIL = var.bootstrap_admin_email }
    secrets = { APP_BOOTSTRAP_ADMIN_PASSWORD = random_password.bootstrap_admin.result }
  } : { config = {}, secrets = {} }
}

module "app_parameters" {
  source = "../../modules/ssm_parameters"

  path       = "/${var.project}/${var.environment}/app"
  parameters = merge(local.default_app_config, local.bootstrap_admin.config, var.app_config)
  secure_parameters = merge(
    {
      POSTGRES_PASSWORD                 = random_password.postgres.result
      JWT_PRIVATE_KEY                   = base64encode(tls_private_key.jwt.private_key_pem_pkcs8)
      JWT_PUBLIC_KEY                    = base64encode(tls_private_key.jwt.public_key_pem)
      GRAFANA_ADMIN_PASSWORD            = random_password.grafana_admin.result
      SERVICE_CLIENT_SECRET             = random_password.service_client_procurement.result
      SERVICE_CLIENT_PROCUREMENT_SECRET = random_password.service_client_procurement.result
      SERVICE_CLIENT_MONOLITH_SECRET    = random_password.service_client_procurement.result # images before the rename; drop after the next release
      SERVICE_CLIENT_MASTERDATA_SECRET  = random_password.service_client_masterdata.result
      SERVICE_CLIENT_FINANCE_SECRET     = random_password.service_client_finance.result
      SERVICE_CLIENT_ANALYTICS_SECRET   = random_password.service_client_analytics.result
      DB_PASSWORD_IDENTITY              = random_password.db_service["identity"].result
      DB_PASSWORD_MASTERDATA            = random_password.db_service["masterdata"].result
      DB_PASSWORD_PROCUREMENT           = random_password.db_service["procurement"].result
      DB_PASSWORD_FINANCE               = random_password.db_service["finance"].result
      DB_PASSWORD_ANALYTICS             = random_password.db_service["analytics"].result
    },
    local.bootstrap_admin.secrets,
    var.extra_secrets,
  )
  tags = local.tags
}

# -------------------------------------------------------------------
# Application host
# -------------------------------------------------------------------
module "instance_iam" {
  source = "../../modules/instance_iam"

  name                = local.name
  ecr_repository_arns = module.ecr.repository_arns
  parameter_path_arns = module.app_parameters.path_arns
  tags                = local.tags
}

module "ec2" {
  source = "../../modules/ec2"

  name                    = local.name
  instance_type           = var.instance_type
  architecture            = var.architecture
  subnet_id               = module.network.public_subnet_ids[0]
  security_group_ids      = [module.security.app_security_group_id]
  instance_profile_name   = module.instance_iam.instance_profile_name
  root_volume_size        = var.root_volume_size
  data_volume_size        = var.data_volume_size
  swap_size_mb            = var.swap_size_mb
  detailed_monitoring     = var.detailed_monitoring
  enable_snapshots        = var.enable_snapshots
  snapshot_retention_days = var.snapshot_retention_days
  tags                    = local.tags
}

# -------------------------------------------------------------------
# GitHub Actions deploys (OIDC -> ECR push + SSM Run Command)
# -------------------------------------------------------------------
module "github_deploy_role" {
  source = "../../modules/github_deploy_role"

  name                 = local.name
  oidc_provider_arn    = var.github_oidc_provider_arn
  github_repository    = var.github_repository
  github_environment   = var.github_environment
  ecr_repository_arns  = module.ecr.repository_arns
  pull_repository_arns = local.promotion_source_repository_arns
  instance_arn         = module.ec2.instance_arn
  tags                 = local.tags
}
