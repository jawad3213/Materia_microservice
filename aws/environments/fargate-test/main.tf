# ===================================================================
# Fargate test environment - main.tf
#
#   internet ──▶ load balancer :80 (allowed_cidrs only) ──▶ frontend (nginx) ──/api──▶ gateway ──▶ services
#
# Every container is an ECS service on Fargate, in public subnets with a public address (no NAT gateway to pay for),
# reachable from the others only, by name: <service>.materia.internal (Cloud Map private DNS). PostgreSQL and Kafka
# run as containers too, on the task's own disk: their data goes with the environment, which is the point of a test
# environment. Attachment files go to an S3 bucket, each service under its own prefix.
#
# Every service is created with no task running: up.sh starts them in order (the database and broker, the database
# users, procurement and its migrations, the users again, the rest), as deploy.sh does on EC2.
# ===================================================================

data "aws_caller_identity" "current" {}

locals {
  name      = "${var.project}-${var.environment}"
  namespace = "materia.internal"
  app_url   = "http://${aws_lb.app.dns_name}"

  host = { for s in ["postgres", "kafka", "procurement", "identity", "masterdata", "finance", "analytics", "gateway"] :
  s => "${s}.${local.namespace}" }

  jdbc_url = "jdbc:postgresql://${local.host.postgres}:5432/materia"
  kafka    = "${local.host.kafka}:29092"
  image    = { for repo, url in module.ecr.repository_urls : repo => "${url}:${var.image_tag}" }

  tags = {
    Project     = var.project
    Environment = var.environment
  }
}

# -------------------------------------------------------------------
# Network: two public subnets (the load balancer needs two zones)
# -------------------------------------------------------------------
module "network" {
  source = "../../modules/network"

  name                = local.name
  vpc_cidr            = var.vpc_cidr
  public_subnet_cidrs = var.public_subnet_cidrs
  tags                = local.tags
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "The load balancer: HTTP from the allowed addresses"
  vpc_id      = module.network.vpc_id

  ingress {
    description = "HTTP from the allowed addresses"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidrs
  }

  egress {
    description = "To the frontend tasks"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr]
  }
}

resource "aws_security_group" "tasks" {
  name        = "${local.name}-tasks"
  description = "The containers: from each other, and the frontend from the load balancer"
  vpc_id      = module.network.vpc_id

  ingress {
    description = "From the other containers"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  ingress {
    description     = "The frontend, from the load balancer"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "Out: ECR, S3, CloudWatch, Parameter Store, the exchange-rate service"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# -------------------------------------------------------------------
# Images, attachment files
# -------------------------------------------------------------------
module "ecr" {
  source = "../../modules/ecr"

  name         = local.name
  repositories = ["procurement", "identity", "masterdata", "finance", "analytics", "gateway", "frontend"]
  keep_images  = 5
  force_delete = true # destroyed with the environment, images included
  tags         = local.tags
}

resource "aws_s3_bucket" "attachments" {
  bucket_prefix = "${local.name}-attachments-"
  force_destroy = true # destroyed with the environment, files included
}

resource "aws_s3_bucket_public_access_block" "attachments" {
  bucket                  = aws_s3_bucket.attachments.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "attachments" {
  bucket = aws_s3_bucket.attachments.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "attachments" {
  bucket = aws_s3_bucket.attachments.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# -------------------------------------------------------------------
# Secrets (Parameter Store), handed to each container that needs them
# -------------------------------------------------------------------
resource "random_password" "postgres" {
  length  = 32
  special = false
}

resource "random_password" "db_service" {
  for_each = toset(["identity", "masterdata", "procurement", "finance", "analytics"])
  length   = 32
  special  = false
}

resource "random_password" "service_client" {
  for_each = toset(["procurement", "masterdata", "finance", "analytics"])
  length   = 48
  special  = false
}

# The demo accounts' password (spring_profile dev): generated, never the well-known default
resource "random_password" "seed" {
  length           = 20
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
  min_special      = 2
  override_special = "!@%^*.,?"
}

resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

locals {
  secret_path = "/${var.project}/${var.environment}/secrets"
  secrets = merge(
    {
      POSTGRES_PASSWORD = random_password.postgres.result
      JWT_PRIVATE_KEY   = base64encode(tls_private_key.jwt.private_key_pem_pkcs8)
      JWT_PUBLIC_KEY    = base64encode(tls_private_key.jwt.public_key_pem)
      APP_SEED_PASSWORD = random_password.seed.result
    },
    { for u, p in random_password.db_service : "DB_PASSWORD_${upper(u)}" => p.result },
    { for c, p in random_password.service_client : "SERVICE_CLIENT_${upper(c)}_SECRET" => p.result },
  )
  # Each secret's parameter, as a container refers to it
  secret_arn = { for name in nonsensitive(keys(local.secrets)) :
  name => "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${local.secret_path}/${name}" }
}

module "secrets" {
  source = "../../modules/ssm_parameters"

  path              = local.secret_path
  secure_parameters = local.secrets
  tags              = local.tags
}

# -------------------------------------------------------------------
# Logs, roles
# -------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.name}"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# ECS itself: pulls the images, writes the logs, reads the secrets of this environment
resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameters"]
      Resource = values(local.secret_arn)
    }]
  })
}

# Each service's own role: ECS Exec (a shell in a running container, for debugging), and for procurement and finance
# their part of the attachment bucket, nothing else.
resource "aws_iam_role" "task" {
  for_each           = local.containers
  name               = "${local.name}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "task_exec" {
  for_each = local.containers
  name     = "ecs-exec"
  role     = aws_iam_role.task[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel"]
      Resource = "*"
    }]
  })
}

resource "aws_iam_role_policy" "task_attachments" {
  for_each = toset(["procurement", "finance"])
  name     = "attachments"
  role     = aws_iam_role.task[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
      Resource = "${aws_s3_bucket.attachments.arn}/${each.key}/*"
    }]
  })
}

# -------------------------------------------------------------------
# The containers
# -------------------------------------------------------------------
locals {
  # What every Spring service reads (the EC2 .env's settings); each container adds its own below.
  spring_env = {
    SPRING_PROFILES_ACTIVE                    = var.spring_profile
    SPRING_DATASOURCE_URL                     = local.jdbc_url
    SPRING_DATASOURCE_DRIVER_CLASS_NAME       = "org.postgresql.Driver"
    SPRING_JPA_HIBERNATE_DIALECT              = "org.hibernate.dialect.PostgreSQLDialect"
    SPRING_JPA_BATCH_SIZE                     = "50"
    SPRING_JPA_ORDER_INSERTS                  = "true"
    SPRING_JPA_ORDER_UPDATES                  = "true"
    APP_MESSAGING_TYPE                        = "kafka"
    APP_OUTBOX_RELAY_ENABLED                  = "true"
    SPRING_KAFKA_BOOTSTRAP_SERVERS            = local.kafka
    SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET   = "earliest"
    SPRING_KAFKA_LISTENER_AUTO_STARTUP        = "true"
    JWT_EXPIRATION_MS                         = "900000"
    APP_CORS_ALLOWED_ORIGINS                  = local.app_url
    APP_LOGIN_URL                             = "${local.app_url}/login"
    APP_RESET_PASSWORD_URL                    = "${local.app_url}/reset-password"
    APP_AUTH_REFRESH_COOKIE_SECURE            = "false" # plain HTTP: no certificate in a test environment
    JAVA_TOOL_OPTIONS                         = "-XX:MaxRAMPercentage=60"
    MANAGEMENT_SERVER_PORT                    = "8081"
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = "health,info,prometheus"
    MANAGEMENT_METRICS_TAGS_ENVIRONMENT       = var.environment
    GRPC_IDENTITY_TARGET                      = "${local.host.identity}:9091"
  }
  spring_secrets = ["JWT_PUBLIC_KEY", "APP_SEED_PASSWORD"]
  spring_health  = ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health/readiness | grep -q UP"]

  containers = {
    postgres = {
      image  = "postgres:16-alpine"
      cpu    = 512
      memory = 1024
      ports  = [5432]
      env = {
        POSTGRES_DB   = "materia"
        POSTGRES_USER = "materia"
      }
      secrets = { POSTGRES_PASSWORD = "POSTGRES_PASSWORD" }
      health  = ["CMD-SHELL", "pg_isready -U materia -d materia"]
    }
    kafka = {
      image  = "apache/kafka:3.7.0"
      cpu    = 512
      memory = 1024
      ports  = [29092]
      env = {
        KAFKA_NODE_ID                                  = "1"
        KAFKA_PROCESS_ROLES                            = "broker,controller"
        KAFKA_CONTROLLER_QUORUM_VOTERS                 = "1@localhost:29093"
        KAFKA_LISTENERS                                = "PLAINTEXT://0.0.0.0:29092,CONTROLLER://localhost:29093"
        KAFKA_ADVERTISED_LISTENERS                     = "PLAINTEXT://${local.kafka}"
        KAFKA_LISTENER_SECURITY_PROTOCOL_MAP           = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
        KAFKA_INTER_BROKER_LISTENER_NAME               = "PLAINTEXT"
        KAFKA_CONTROLLER_LISTENER_NAMES                = "CONTROLLER"
        KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR         = "1"
        KAFKA_TRANSACTION_STATE_LOG_MIN_ISR            = "1"
        KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR = "1"
        KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS         = "0"
        KAFKA_HEAP_OPTS                                = "-Xmx512m -Xms512m"
      }
      secrets = {}
      health  = null
    }
    procurement = {
      image  = local.image.procurement
      cpu    = 1024
      memory = 2048
      ports  = [8080, 9090]
      env = merge(local.spring_env, {
        SPRING_DATASOURCE_USERNAME   = "materia_procurement"
        SPRING_FLYWAY_DEFAULT_SCHEMA = "public"
        JWT_PRIVATE_KEY              = ""
        GRPC_MASTERDATA_TARGET       = "${local.host.masterdata}:9092"
        APP_ATTACHMENTS_STORAGE      = "s3"
        APP_ATTACHMENTS_S3_BUCKET    = aws_s3_bucket.attachments.bucket
      })
      secrets = merge({ for s in local.spring_secrets : s => s }, {
        SPRING_DATASOURCE_PASSWORD = "DB_PASSWORD_PROCUREMENT"
        SERVICE_CLIENT_SECRET      = "SERVICE_CLIENT_PROCUREMENT_SECRET"
      })
      health = local.spring_health
    }
    identity = {
      image  = local.image.identity
      cpu    = 512
      memory = 1024
      ports  = [8082, 9091]
      env = merge(local.spring_env, {
        SPRING_DATASOURCE_USERNAME   = "materia_identity"
        SPRING_FLYWAY_DEFAULT_SCHEMA = "identity"
        SPRINGDOC_INFO_TITLE         = "Materia Identity API"
      })
      secrets = merge({ for s in local.spring_secrets : s => s }, {
        SPRING_DATASOURCE_PASSWORD        = "DB_PASSWORD_IDENTITY"
        JWT_PRIVATE_KEY                   = "JWT_PRIVATE_KEY"
        SERVICE_CLIENT_PROCUREMENT_SECRET = "SERVICE_CLIENT_PROCUREMENT_SECRET"
        SERVICE_CLIENT_MASTERDATA_SECRET  = "SERVICE_CLIENT_MASTERDATA_SECRET"
        SERVICE_CLIENT_FINANCE_SECRET     = "SERVICE_CLIENT_FINANCE_SECRET"
        SERVICE_CLIENT_ANALYTICS_SECRET   = "SERVICE_CLIENT_ANALYTICS_SECRET"
      })
      health = local.spring_health
    }
    masterdata = {
      image  = local.image.masterdata
      cpu    = 512
      memory = 1024
      ports  = [8083, 9092]
      env = merge(local.spring_env, {
        SPRING_DATASOURCE_USERNAME   = "materia_masterdata"
        SPRING_FLYWAY_DEFAULT_SCHEMA = "masterdata"
        JWT_PRIVATE_KEY              = ""
        GRPC_PROCUREMENT_TARGET      = "${local.host.procurement}:9090"
        SPRINGDOC_INFO_TITLE         = "Materia Master Data API"
      })
      secrets = merge({ for s in local.spring_secrets : s => s }, {
        SPRING_DATASOURCE_PASSWORD       = "DB_PASSWORD_MASTERDATA"
        SERVICE_CLIENT_MASTERDATA_SECRET = "SERVICE_CLIENT_MASTERDATA_SECRET"
      })
      health = local.spring_health
    }
    finance = {
      image  = local.image.finance
      cpu    = 512
      memory = 1024
      ports  = [8084, 9093]
      env = merge(local.spring_env, {
        SPRING_DATASOURCE_USERNAME   = "materia_finance"
        SPRING_FLYWAY_DEFAULT_SCHEMA = "finance"
        JWT_PRIVATE_KEY              = ""
        GRPC_PROCUREMENT_TARGET      = "${local.host.procurement}:9090"
        APP_ATTACHMENTS_STORAGE      = "s3"
        APP_ATTACHMENTS_S3_BUCKET    = aws_s3_bucket.attachments.bucket
        SPRINGDOC_INFO_TITLE         = "Materia Finance API"
      })
      secrets = merge({ for s in local.spring_secrets : s => s }, {
        SPRING_DATASOURCE_PASSWORD    = "DB_PASSWORD_FINANCE"
        SERVICE_CLIENT_FINANCE_SECRET = "SERVICE_CLIENT_FINANCE_SECRET"
      })
      health = local.spring_health
    }
    analytics = {
      image  = local.image.analytics
      cpu    = 512
      memory = 1024
      ports  = [8085]
      env = merge(local.spring_env, {
        SPRING_DATASOURCE_USERNAME   = "materia_analytics"
        SPRING_FLYWAY_DEFAULT_SCHEMA = "analytics"
        APP_MESSAGING_TYPE           = "spring"
        APP_OUTBOX_RELAY_ENABLED     = "false"
        JWT_PRIVATE_KEY              = ""
        GRPC_PROCUREMENT_TARGET      = "${local.host.procurement}:9090"
        GRPC_MASTERDATA_TARGET       = "${local.host.masterdata}:9092"
        GRPC_FINANCE_TARGET          = "${local.host.finance}:9093"
        SPRINGDOC_INFO_TITLE         = "Materia Analytics API"
      })
      secrets = merge({ for s in local.spring_secrets : s => s }, {
        SPRING_DATASOURCE_PASSWORD      = "DB_PASSWORD_ANALYTICS"
        SERVICE_CLIENT_ANALYTICS_SECRET = "SERVICE_CLIENT_ANALYTICS_SECRET"
      })
      health = local.spring_health
    }
    gateway = {
      image  = local.image.gateway
      cpu    = 256
      memory = 512
      ports  = [8080]
      env = {
        GATEWAY_PROCUREMENT_URI                    = "http://${local.host.procurement}:8080"
        GATEWAY_IDENTITY_URI                       = "http://${local.host.identity}:8082"
        GATEWAY_MASTERDATA_URI                     = "http://${local.host.masterdata}:8083"
        GATEWAY_FINANCE_URI                        = "http://${local.host.finance}:8084"
        GATEWAY_ANALYTICS_URI                      = "http://${local.host.analytics}:8085"
        GATEWAY_RATE_LIMIT_MAX_REQUESTS_PER_MINUTE = tostring(var.api_rate_limit_per_minute)
        MANAGEMENT_SERVER_PORT                     = "8081"
        MANAGEMENT_METRICS_TAGS_ENVIRONMENT        = var.environment
        JAVA_TOOL_OPTIONS                          = "-XX:MaxRAMPercentage=60"
      }
      secrets = {}
      health  = local.spring_health
    }
    frontend = {
      image  = local.image.frontend
      cpu    = 256
      memory = 512
      ports  = [80]
      env = {
        MATERIA_GATEWAY_UPSTREAM = "${local.host.gateway}:8080"
        # The load balancer is in the VPC: its X-Forwarded-For gives the client's address (the gateway's rate limit)
        MATERIA_TRUSTED_PROXY = var.vpc_cidr
      }
      secrets = {}
      health  = null
    }
  }
}

resource "aws_ecs_cluster" "this" {
  name = local.name
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = local.namespace
  description = "The containers of ${local.name}, by name"
  vpc         = module.network.vpc_id
}

resource "aws_service_discovery_service" "this" {
  for_each = local.containers
  name     = each.key

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

resource "aws_ecs_task_definition" "this" {
  for_each                 = local.containers
  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task[each.key].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([merge(
    {
      name         = each.key
      image        = each.value.image
      essential    = true
      portMappings = [for p in each.value.ports : { containerPort = p, protocol = "tcp" }]
      environment  = [for k, v in each.value.env : { name = k, value = v }]
      secrets      = [for k, v in each.value.secrets : { name = k, valueFrom = local.secret_arn[v] }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = each.key
        }
      }
    },
    each.value.health == null ? {} : {
      healthCheck = {
        command     = each.value.health
        interval    = 15
        timeout     = 5
        retries     = 5
        startPeriod = 240 # a Spring service on a small Fargate task takes a couple of minutes to start
      }
    }
  )])
}

resource "aws_ecs_service" "this" {
  for_each               = local.containers
  name                   = each.key
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.this[each.key].arn
  launch_type            = "FARGATE"
  desired_count          = 0 # started by up.sh, in order
  enable_execute_command = true
  force_delete           = true

  network_configuration {
    subnets          = module.network.public_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true # to reach ECR and the internet without a NAT gateway
  }

  service_registries {
    registry_arn = aws_service_discovery_service.this[each.key].arn
  }

  dynamic "load_balancer" {
    for_each = each.key == "frontend" ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.frontend.arn
      container_name   = "frontend"
      container_port   = 80
    }
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener.http, module.secrets]
}

# The database users, run by up.sh as a one-off task (aws/deploy/db/service-roles.sql, the script deploy.sh runs on
# EC2): before procurement starts, and again once it has migrated.
resource "aws_ecs_task_definition" "db_roles" {
  family                   = "${local.name}-db-roles"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn

  container_definitions = jsonencode([{
    name      = "db-roles"
    image     = "postgres:16-alpine"
    essential = true
    command = ["sh", "-c", join(" ", [
      "echo \"$ROLES_SQL\" | base64 -d > /tmp/service-roles.sql &&",
      "psql -q -v ON_ERROR_STOP=1",
      "-v identity_password=\"$DB_PASSWORD_IDENTITY\" -v masterdata_password=\"$DB_PASSWORD_MASTERDATA\"",
      "-v procurement_password=\"$DB_PASSWORD_PROCUREMENT\" -v finance_password=\"$DB_PASSWORD_FINANCE\"",
      "-v analytics_password=\"$DB_PASSWORD_ANALYTICS\" -f /tmp/service-roles.sql",
    ])]
    environment = [
      { name = "PGHOST", value = local.host.postgres },
      { name = "PGUSER", value = "materia" },
      { name = "PGDATABASE", value = "materia" },
      { name = "ROLES_SQL", value = filebase64("${path.module}/../../deploy/db/service-roles.sql") },
    ]
    secrets = [for name in ["POSTGRES_PASSWORD", "DB_PASSWORD_IDENTITY", "DB_PASSWORD_MASTERDATA",
      "DB_PASSWORD_PROCUREMENT", "DB_PASSWORD_FINANCE", "DB_PASSWORD_ANALYTICS"] :
    { name = name == "POSTGRES_PASSWORD" ? "PGPASSWORD" : name, valueFrom = local.secret_arn[name] }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "db-roles"
      }
    }
  }])
}

# -------------------------------------------------------------------
# The load balancer, in front of the frontend
# -------------------------------------------------------------------
resource "aws_lb" "app" {
  name               = substr(local.name, 0, 32)
  load_balancer_type = "application"
  internal           = false
  subnets            = module.network.public_subnet_ids
  security_groups    = [aws_security_group.alb.id]
  idle_timeout       = 120 # attachment uploads, slow first calls
}

resource "aws_lb_target_group" "frontend" {
  name                 = substr("${local.name}-web", 0, 32)
  port                 = 80
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = module.network.vpc_id
  deregistration_delay = 10

  health_check {
    path                = "/"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}
