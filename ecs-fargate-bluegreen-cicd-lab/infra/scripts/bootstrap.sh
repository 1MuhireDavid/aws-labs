#!/usr/bin/env bash
# OPTIONAL CLI ALTERNATIVE. The recommended path for this lab is the
# AWS Console walkthrough in ../README.md ("One-time bootstrap"), which
# deploys this exact same template with no CLI required. Use this script
# only if you'd rather script it -- it creates identical resources.
#
# Usage:
#   ./bootstrap.sh <github-org> <repo-name> [aws-region]
#
# Example:
#   ./bootstrap.sh 1MuhireDavid aws-labs us-east-1

set -euo pipefail

GITHUB_ORG="${1:?Usage: bootstrap.sh <github-org> <repo-name> [aws-region]}"
REPO_NAME="${2:?Missing repo name (e.g. aws-labs)}"
AWS_REGION="${3:-us-east-1}"
STACK_NAME="ecs-bluegreen-lab-bootstrap"

echo "Deploying bootstrap stack '${STACK_NAME}' in ${AWS_REGION}..."

aws cloudformation deploy \
  --region "${AWS_REGION}" \
  --stack-name "${STACK_NAME}" \
  --template-file "$(dirname "$0")/../cfn/bootstrap/00-bootstrap.yaml" \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
      GitHubOrg="${GITHUB_ORG}" \
      RepoName="${REPO_NAME}" \
      CreateOidcProvider=true

echo
echo "Bootstrap complete. Values to configure next:"
echo "----------------------------------------------------------------"

TEMPLATES_BUCKET=$(aws cloudformation describe-stacks --region "${AWS_REGION}" \
  --stack-name "${STACK_NAME}" \
  --query "Stacks[0].Outputs[?OutputKey=='TemplatesBucketName'].OutputValue" --output text)

INFRA_ROLE_ARN=$(aws cloudformation describe-stacks --region "${AWS_REGION}" \
  --stack-name "${STACK_NAME}" \
  --query "Stacks[0].Outputs[?OutputKey=='InfraPackagingRoleArn'].OutputValue" --output text)

APP_ROLE_ARN=$(aws cloudformation describe-stacks --region "${AWS_REGION}" \
  --stack-name "${STACK_NAME}" \
  --query "Stacks[0].Outputs[?OutputKey=='AppEcrPushRoleArn'].OutputValue" --output text)

cat <<EOF

Both workflows live in the SAME repo (${GITHUB_ORG}/${REPO_NAME}), so all
secrets below go into that one repo's Settings -> Secrets and variables
-> Actions. The two roles stay isolated from each other via GitHub's
job_workflow_ref OIDC claim (see 00-bootstrap.yaml), not by living in
separate repos.

1) Add these secrets to ${GITHUB_ORG}/${REPO_NAME}:
     AWS_INFRA_PACKAGING_ROLE_ARN = ${INFRA_ROLE_ARN}
     AWS_TEMPLATES_BUCKET         = ${TEMPLATES_BUCKET}
     AWS_ECR_PUSH_ROLE_ARN        = ${APP_ROLE_ARN}
     AWS_REGION                   = ${AWS_REGION}
     ECR_REPOSITORY               = ecs-bluegreen-lab-app

2) In ecs-fargate-bluegreen-cicd-lab/infra/cfn/deployment-file.yaml, set:
     TemplatesBucketName: ${TEMPLATES_BUCKET}
     AppOwnerName: "<your full name>"
     GitHubOrg: "${GITHUB_ORG}"
     RepoName: "${REPO_NAME}"

3) Push to ${GITHUB_ORG}/${REPO_NAME} (main branch) so
   .github/workflows/ecs-bluegreen-lab-infra-package-templates.yml runs
   once and uploads cfn/modules/*.yaml to S3.

4) In the CloudFormation console, create a new stack -> "Sync from Git" ->
   point it at ${GITHUB_ORG}/${REPO_NAME}, branch main, deployment file
   ecs-fargate-bluegreen-cicd-lab/infra/cfn/deployment-file.yaml. Merge
   the pull request Git sync opens.
----------------------------------------------------------------
EOF
