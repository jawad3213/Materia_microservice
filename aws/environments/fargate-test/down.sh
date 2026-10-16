#!/usr/bin/env bash
# ===================================================================
# Destroys the Fargate test environment: every container, the load balancer, the network, the images (ECR), the
# attachment files (S3), the secrets and the logs. Its PostgreSQL and Kafka data go with it: nothing is kept.
# Terraform lists what it will delete and asks for "yes" first.
# ===================================================================
set -euo pipefail
cd "$(dirname "$0")"

[ -f terraform.tfstate ] || { echo "Nothing to destroy: no terraform.tfstate here."; exit 0; }

terraform init -input=false >/dev/null
# The image tag only matters to a running environment
terraform destroy -var image_tag=destroy "$@"
