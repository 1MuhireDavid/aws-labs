# Elastic Beanstalk Java Deployment (Spring Boot + CI/CD + DynamoDB)

A minimal Spring Boot application deployed to AWS Elastic Beanstalk (Java SE
platform), with a GitHub Actions pipeline that builds the app, uploads a
versioned source bundle to S3, and deploys it automatically on every push
to `main`. Includes an optional DynamoDB integration to demonstrate a real
external backend dependency.

## Architecture

```
GitHub push (main)
   │
   ▼
GitHub Actions workflow (.github/workflows/deploy.yml)
   1. mvn clean package                → target/application.jar
   2. zip jar + Procfile + .ebextensions → source bundle
   3. aws s3 cp                         → s3://<bucket>/<app>/<version>.zip
   4. aws elasticbeanstalk create-application-version
   5. aws elasticbeanstalk update-environment (deploy + set env vars)
   6. wait + smoke test
   │
   ▼
Elastic Beanstalk environment (Java SE, load balanced, auto-scaled)
   │
   ▼
EC2 instances (fully managed by EB) ── DynamoDB table (external service)
```

Elastic Beanstalk owns the load balancer, auto scaling group, and EC2
instances — nothing is provisioned or managed by hand.

## Endpoints

| Path         | Purpose                                                            |
|--------------|---------------------------------------------------------------------|
| `/`          | Confirms the app is running; returns version + commit + timestamp   |
| `/version`   | JSON version/commit/build-time, used to prove a redeploy took effect|
| `/db-check`  | Writes/reads a heartbeat record in DynamoDB (external service check)|
| `/actuator/health` | Standard Spring Boot health endpoint                          |

## Repository layout

```
pom.xml                          Maven build (Java 17, Spring Boot, AWS SDK v2)
Procfile                         Tells EB Java SE platform how to run the jar
.ebextensions/01-environment.config   Health check + rolling deployment policy
src/main/java/...                Application source
.github/workflows/deploy.yml     CI/CD pipeline
```

---

## 1. One-time AWS setup

All commands assume the AWS CLI is configured locally with an account that
has admin/setup rights. Replace placeholders in `<angle brackets>`.

### 1.1 Create the S3 bucket for source bundles

```bash
aws s3 mb s3://<your-eb-source-bucket> --region <your-region>
aws s3api put-bucket-versioning \
  --bucket <your-eb-source-bucket> \
  --versioning-configuration Status=Enabled
```

### 1.2 Create the Elastic Beanstalk application and environment

The very first deployment also goes through S3 (this is how EB works even
from the console), so build the jar once locally and upload it manually to
seed the environment — the GitHub Actions workflow takes over from the
second deployment onward.

```bash
# Build locally
mvn clean package
mkdir -p deploy && cp target/application.jar Procfile deploy/ && cp -r .ebextensions deploy/
(cd deploy && zip -r ../initial.zip .)

# Create the application
aws elasticbeanstalk create-application \
  --application-name eb-java-app \
  --description "Java Elastic Beanstalk demo"

# Upload initial bundle to S3 and register it as version v0
aws s3 cp initial.zip s3://<your-eb-source-bucket>/eb-java-app/v0-initial.zip
aws elasticbeanstalk create-application-version \
  --application-name eb-java-app \
  --version-label v0-initial \
  --source-bundle S3Bucket=<your-eb-source-bucket>,S3Key=eb-java-app/v0-initial.zip

# Create the environment (Java SE platform - EB manages EC2, ALB, ASG)
aws elasticbeanstalk create-environment \
  --application-name eb-java-app \
  --environment-name eb-java-app-prod \
  --solution-stack-name "64bit Amazon Linux 2023 v4.4.1 running Corretto 17" \
  --version-label v0-initial \
  --option-settings \
      Namespace=aws:autoscaling:launchconfiguration,OptionName=IamInstanceProfile,Value=aws-elasticbeanstalk-ec2-role \
      Namespace=aws:elasticbeanstalk:environment,OptionName=EnvironmentType,Value=LoadBalanced
```

> Tip: run `aws elasticbeanstalk list-available-solution-stacks | grep Corretto`
> to get the exact current platform string for your region.

Wait for it to go green, then get the public URL:

```bash
aws elasticbeanstalk describe-environments \
  --environment-names eb-java-app-prod \
  --query "Environments[0].CNAME" --output text
```

That is your **Elastic Beanstalk application URL** deliverable.

### 1.3 (Optional challenge) Create the DynamoDB table

```bash
aws dynamodb create-table \
  --table-name eb-app-heartbeat \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST
```

Grant the EB EC2 instance role (`aws-elasticbeanstalk-ec2-role`) permission
to use it:

```bash
aws iam put-role-policy \
  --role-name aws-elasticbeanstalk-ec2-role \
  --policy-name eb-app-dynamodb-access \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:DescribeTable"],
      "Resource": "arn:aws:dynamodb:*:*:table/eb-app-heartbeat"
    }]
  }'
```

Set the table name and region as **Elastic Beanstalk environment
variables** (no code changes needed — the app reads these at runtime):

```bash
aws elasticbeanstalk update-environment \
  --environment-name eb-java-app-prod \
  --option-settings \
    Namespace=aws:elasticbeanstalk:application:environment,OptionName=DYNAMODB_TABLE_NAME,Value=eb-app-heartbeat \
    Namespace=aws:elasticbeanstalk:application:environment,OptionName=AWS_REGION,Value=<your-region>
```

### 1.4 Create the IAM role GitHub Actions will assume (OIDC — no static keys)

Register GitHub as an OIDC identity provider (one-time per AWS account):

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

Create a trust policy scoped to this repo only (`trust-policy.json`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<account-id>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike": { "token.actions.githubusercontent.com:sub": "repo:<your-org>/<your-repo>:ref:refs/heads/main" }
    }
  }]
}
```

```bash
aws iam create-role \
  --role-name github-actions-eb-deploy \
  --assume-role-policy-document file://trust-policy.json
```

Attach a **least-privilege** permissions policy (`deploy-policy.json`) —
only S3 access to the source bucket and EB deploy actions, nothing else:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::<your-eb-source-bucket>/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "elasticbeanstalk:CreateApplicationVersion",
        "elasticbeanstalk:UpdateEnvironment",
        "elasticbeanstalk:DescribeEnvironments",
        "elasticbeanstalk:DescribeApplicationVersions"
      ],
      "Resource": "*"
    }
  ]
}
```

```bash
aws iam put-role-policy \
  --role-name github-actions-eb-deploy \
  --policy-name eb-deploy-least-privilege \
  --policy-document file://deploy-policy.json
```

---

## 2. GitHub repository setup

Push this project to a public GitHub repository, then configure:

**Settings → Secrets and variables → Actions → Secrets**
| Name | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::<account-id>:role/github-actions-eb-deploy` |

**Settings → Secrets and variables → Actions → Variables**
| Name | Value |
|---|---|
| `AWS_REGION` | e.g. `us-east-1` |
| `EB_SOURCE_BUCKET` | `<your-eb-source-bucket>` |
| `EB_APPLICATION_NAME` | `eb-java-app` |
| `EB_ENVIRONMENT_NAME` | `eb-java-app-prod` |

No AWS access keys are ever stored in GitHub — authentication happens via
short-lived OIDC tokens exchanged for the IAM role above.

---

## 3. Day-to-day workflow

1. Make a code change, commit, `git push origin main`.
2. GitHub Actions automatically: builds → packages → uploads to S3 →
   creates a new EB application version → deploys it → waits for the
   environment to report healthy → curls `/version` as a smoke test.
3. Check the **Elastic Beanstalk console → Application versions** to see
   the growing, timestamped version history.
4. Visit `http://<EB_CNAME>/version` to confirm the new commit/build time.

## 4. Live review checklist

- [ ] Open the EB URL in a browser → shows the JSON confirmation payload
- [ ] Make a small visible change (e.g. edit the message string), push to `main`
- [ ] Show the GitHub Actions run completing end to end
- [ ] Refresh `/version` → new commit hash / build time appears
- [ ] Open EB console → Application versions → show version history
- [ ] Hit `/db-check` → shows `"status": "CONNECTED"` with a live DynamoDB write

## 5. Rollback

Because every deployment is a tracked, immutable application version,
rolling back is a single command (no rebuild needed):

```bash
aws elasticbeanstalk update-environment \
  --environment-name eb-java-app-prod \
  --version-label <previous-version-label>
```
