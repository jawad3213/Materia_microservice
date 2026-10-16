#!/usr/bin/env bash
# ===================================================================
# Brings the Fargate test environment up: builds and pushes the images of the current commit, creates the
# infrastructure, then starts the containers in order, as deploy.sh does on EC2:
#   1. PostgreSQL and Kafka
#   2. the services' database users (aws/deploy/db/service-roles.sql, as a one-off task)
#   3. procurement, whose migrations create the services' schemas
#   4. the database users again (each schema handed to its service)
#   5. identity, master data, finance, analytics, then the gateway and the frontend
#
# Needs: terraform.tfvars (see terraform.tfvars.example), the AWS CLI with credentials for the account, Docker,
# Java 21 (the Maven wrapper builds the services). Run from Git Bash on Windows.
#   ./up.sh            build, push, create, start
#   ./up.sh --no-build start the images already pushed for this commit
# Destroy everything with ./down.sh.
# ===================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
cd "$HERE"

BUILD=true
[ "${1:-}" = "--no-build" ] && BUILD=false

log() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[ -f terraform.tfvars ] || fail "terraform.tfvars is missing: copy terraform.tfvars.example and set allowed_cidrs"
for tool in terraform aws docker; do
  command -v "$tool" >/dev/null || fail "$tool is not installed"
done
aws sts get-caller-identity >/dev/null || fail "the AWS CLI has no valid credentials"

# Images are tagged with the commit; ECR tags are immutable, so uncommitted changes get a tag of their own.
TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
  TAG="$TAG-dirty-$(date +%s)"
fi
export TF_VAR_image_tag="$TAG"
log "Images tagged $TAG"

terraform init -input=false >/dev/null

# ---- Images ---------------------------------------------------------------------------------------------------
if $BUILD; then
  log "Creating the image repositories"
  terraform apply -input=false -auto-approve -target=module.ecr >/dev/null

  REGISTRY="$(terraform output -raw registry)"
  REGION="$(terraform output -raw region)"
  aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null

  log "Building the services (Maven)"
  (cd "$ROOT/backend" && ./mvnw -q -B package -DskipTests -Djacoco.skip=true \
    -pl procurement-service,identity-service,masterdata-service,finance-service,analytics-service,gateway-service -am)

  NAME="$(terraform output -raw name)"
  repo() { echo "$REGISTRY/$NAME-$1"; }
  push() { # push <repository> <docker build arguments...>
    local name="$1"; shift
    local target; target="$(repo "$name"):$TAG"
    log "Image $name"
    docker build -q -t "$target" "$@" >/dev/null
    docker push -q "$target" >/dev/null
  }
  for service in procurement identity masterdata finance analytics gateway; do
    push "$service" "$ROOT/backend/$service-service"
  done
  push frontend --build-arg VITE_API_BASE_URL=/api/v1 "$ROOT/frontend"
fi

# ---- Infrastructure -------------------------------------------------------------------------------------------
log "Creating the infrastructure"
terraform apply -input=false -auto-approve

CLUSTER="$(terraform output -raw cluster)"
REGION="$(terraform output -raw region)"
SUBNETS="$(terraform output -raw subnets)"
SG="$(terraform output -raw tasks_security_group)"
DB_ROLES="$(terraform output -raw db_roles_task_definition)"
export AWS_REGION="$REGION"

start() { # start <service>...
  for s in "$@"; do
    aws ecs update-service --cluster "$CLUSTER" --service "$s" --desired-count 1 --force-new-deployment >/dev/null
  done
}

# Waits until the service's task reports its container healthy (or running, for a container without a check)
wait_ready() { # wait_ready <service> [minutes]
  local service="$1" minutes="${2:-10}" status task
  printf '    waiting for %s' "$service"
  for _ in $(seq 1 $((minutes * 6))); do
    task="$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$service" --desired-status RUNNING \
      --query 'taskArns[0]' --output text)"
    if [ "$task" != "None" ] && [ -n "$task" ]; then
      status="$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" \
        --query 'tasks[0].[lastStatus,healthStatus]' --output text)"
      case "$status" in
        "RUNNING"*"HEALTHY"*)
          printf ' ready\n'
          return 0
          ;;
        "RUNNING"*"UNKNOWN"*)
          # Kafka and the frontend have no health check: running is ready
          if [ "$service" = "kafka" ] || [ "$service" = "frontend" ]; then
            printf ' running\n'
            return 0
          fi
          ;;
      esac
    fi
    printf '.'
    sleep 10
  done
  printf '\n'
  fail "$service is not ready after $minutes minutes: aws logs tail $(terraform output -raw log_group) --log-stream-name-prefix $service"
}

# Runs the database users script, retrying while PostgreSQL's name is not resolvable yet
db_roles() {
  local task code
  for attempt in 1 2 3 4 5; do
    task="$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$DB_ROLES" --launch-type FARGATE \
      --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=ENABLED}" \
      --query 'tasks[0].taskArn' --output text)"
    aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$task"
    code="$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" --query 'tasks[0].containers[0].exitCode' \
      --output text)"
    [ "$code" = "0" ] && { echo "    database users applied"; return 0; }
    echo "    attempt $attempt failed (exit $code), retrying"
    sleep 15
  done
  fail "the database users could not be applied: aws logs tail $(terraform output -raw log_group) --log-stream-name-prefix db-roles"
}

log "1. PostgreSQL and Kafka"
start postgres kafka
wait_ready postgres 5
wait_ready kafka 5

log "2. The services' database users"
db_roles

log "3. Procurement (its migrations create the services' schemas)"
start procurement
wait_ready procurement 15

log "4. The database users again"
db_roles

log "5. The other services"
start identity masterdata finance analytics
for s in identity masterdata finance analytics; do wait_ready "$s" 15; done
start gateway frontend
wait_ready gateway 10
wait_ready frontend 5
aws elbv2 wait target-in-service --target-group-arn "$(terraform output -raw frontend_target_group)"

log "Up: $(terraform output -raw app_url)"
if grep -qE '^\s*spring_profile\s*=\s*"prod"' terraform.tfvars; then
  echo "    spring_profile prod: no demo account."
else
  echo "    Demo accounts: admin@materia.com, purchaser@materia.com, receiver@materia.com"
  echo "    Their password: terraform output -raw demo_password"
fi
echo "    Logs: aws logs tail $(terraform output -raw log_group) --follow --log-stream-name-prefix <service>"
echo "    It costs money while it runs: ./down.sh destroys everything."
