# Elastic Beanstalk Java Deployment (Spring Boot + CI/CD + DynamoDB)

A minimal Spring Boot application deployed to AWS Elastic Beanstalk (Java SE
platform), with a GitHub Actions pipeline that builds the app, uploads a
versioned source bundle to S3, and deploys it automatically on every push
to `main`. Includes a DynamoDB integration demonstrating a real external
backend dependency.

## Live deployment

- **Application URL:** http://eb-java-app-prod.eba-zay7wjpw.eu-north-1.elasticbeanstalk.com
- **Region:** `eu-north-1`
- **Elastic Beanstalk application / environment:** `eb-java-app` / `eb-java-app-prod`
- **Platform:** `64bit Amazon Linux 2023 v4.12.5 running Corretto 17`
- **Source bundle bucket:** `eb-java-app-source-047719661196`
- **External service:** DynamoDB table `eb-app-heartbeat`

> Note: this project lives in the `eb-java-app/` subfolder of a multi-lab
> repository. The GitHub Actions workflow sits at the repository root
> (`.github/workflows/deploy.yml`) and is scoped to build only when files
> under `eb-java-app/` change.

## Architecture

```
GitHub push (main, changes under eb-java-app/**)
   |
   v
GitHub Actions workflow (.github/workflows/deploy.yml)
   1. mvn clean package                 -> target/application.jar
   2. zip jar + Procfile + .ebextensions -> source bundle
   3. aws s3 cp                          -> s3://eb-java-app-source-047719661196/eb-java-app/<version>.zip
   4. aws elasticbeanstalk create-application-version
   5. aws elasticbeanstalk update-environment (deploy + set APP_COMMIT / APP_BUILD_TIME)
   6. wait for green + smoke test /version
   |
   v
Elastic Beanstalk environment (Java SE, load balanced, auto-scaled)
   |
   v
EC2 instances (fully managed by EB) --- DynamoDB table eb-app-heartbeat
```

Elastic Beanstalk owns the load balancer, auto scaling group, and EC2
instances. Authentication from GitHub Actions to AWS uses short-lived OIDC
tokens exchanged for an IAM role. No static AWS access keys exist anywhere
in the repository or GitHub secrets.

## Endpoints

| Path               | Purpose                                                             |
|--------------------|---------------------------------------------------------------------|
| `/`                | Confirms the app is running; returns version + commit + timestamp   |
| `/version`         | JSON version/commit/build-time, used to prove a redeploy took effect |
| `/db-check`        | Writes/reads a heartbeat record in DynamoDB (external service check) |
| `/actuator/health` | Standard Spring Boot health endpoint                                |

Example `/db-check` response once the table and env vars are configured:

```json
{"status":"CONNECTED","table":"eb-app-heartbeat","tableStatus":"ACTIVE","heartbeatId":"..."}
```

## Repository layout

```
eb-java-app/
  pom.xml                              Maven build (Java 17, Spring Boot, AWS SDK v2)
  Procfile                             Tells EB Java SE platform how to run the jar
  .ebextensions/01-environment.config  Health check + rolling deployment policy
  src/main/java/...                    Application source
.github/workflows/deploy.yml           CI/CD pipeline (at repo root)
```

---

## 1. One-time AWS setup

All commands assume the AWS CLI is configured with a profile named `admin`
that has setup rights, and that everything lives in `eu-north-1`. Account ID
used here is `047719661196` - substitute your own where relevant.

### 1.1 Create the S3 bucket for source bundles

```bash
aws s3 mb s3://eb-java-app-source-047719661196 --region eu-north-1 --profile admin
aws s3api put-bucket-versioning \
  --bucket eb-java-app-source-047719661196 \
  --versioning-configuration Status=Enabled \
  --profile admin
```

### 1.2 Create the EB EC2 instance profile (fresh accounts only)

On a brand-new account the default instance profile does not exist yet
(it is normally auto-created by the EB console). Create it manually:

```bash
aws iam create-role \
  --role-name aws-elasticbeanstalk-ec2-role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": { "Service": "ec2.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }]
  }' \
  --profile admin

aws iam attach-role-policy --role-name aws-elasticbeanstalk-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AWSElasticBeanstalkWebTier --profile admin
aws iam attach-role-policy --role-name aws-elasticbeanstalk-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AWSElasticBeanstalkWorkerTier --profile admin
aws iam attach-role-policy --role-name aws-elasticbeanstalk-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AWSElasticBeanstalkMulticontainerDocker --profile admin

aws iam create-instance-profile \
  --instance-profile-name aws-elasticbeanstalk-ec2-role --profile admin
aws iam add-role-to-instance-profile \
  --instance-profile-name aws-elasticbeanstalk-ec2-role \
  --role-name aws-elasticbeanstalk-ec2-role --profile admin
```

### 1.3 Build the initial source bundle and create the application/environment

The very first deployment also goes through S3 (this is how EB works even
from the console). Build the jar once and upload it manually to seed the
environment; the GitHub Actions workflow takes over from then on.

Important: build the zip with a Linux `zip` tool (e.g. WSL), not PowerShell's
`Compress-Archive`, which writes Windows backslash path separators that the
Linux EB instance cannot unzip.

```bash
mvn clean package
mkdir -p deploy
cp target/application.jar deploy/
cp Procfile deploy/
cp -r .ebextensions deploy/
(cd deploy && zip -r ../initial.zip .)

aws s3 cp initial.zip \
  s3://eb-java-app-source-047719661196/eb-java-app/v0-initial.zip --profile admin

aws elasticbeanstalk create-application \
  --application-name eb-java-app \
  --description "Java Elastic Beanstalk demo" \
  --profile admin --region eu-north-1

aws elasticbeanstalk create-application-version \
  --application-name eb-java-app \
  --version-label v0-initial \
  --source-bundle S3Bucket=eb-java-app-source-047719661196,S3Key=eb-java-app/v0-initial.zip \
  --profile admin --region eu-north-1

aws elasticbeanstalk create-environment \
  --application-name eb-java-app \
  --environment-name eb-java-app-prod \
  --solution-stack-name "64bit Amazon Linux 2023 v4.12.5 running Corretto 17" \
  --version-label v0-initial \
  --option-settings \
      Namespace=aws:autoscaling:launchconfiguration,OptionName=IamInstanceProfile,Value=aws-elasticbeanstalk-ec2-role \
      Namespace=aws:elasticbeanstalk:environment,OptionName=EnvironmentType,Value=LoadBalanced \
  --profile admin --region eu-north-1
```

> The Corretto 17 solution-stack string changes over time. Confirm the current
> value for your region with:
> `aws elasticbeanstalk list-available-solution-stacks --profile admin --region eu-north-1 --query "SolutionStacks[?contains(@, 'Corretto 17')]"`

Get the public URL once the environment is green:

```bash
aws elasticbeanstalk describe-environments \
  --environment-names eb-java-app-prod \
  --profile admin --region eu-north-1 \
  --query "Environments[0].CNAME" --output text
```

### 1.4 Create the DynamoDB table (external service integration)

```bash
aws dynamodb create-table \
  --table-name eb-app-heartbeat \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --profile admin --region eu-north-1
```

Grant the EB EC2 instance role permission to use it:

```bash
aws iam put-role-policy \
  --role-name aws-elasticbeanstalk-ec2-role \
  --policy-name eb-app-dynamodb-access \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:DescribeTable"],
      "Resource": "arn:aws:dynamodb:eu-north-1:047719661196:table/eb-app-heartbeat"
    }]
  }' \
  --profile admin
```

Set the table name and region as Elastic Beanstalk environment variables
(no code change required - the app reads these at runtime). The env var name
must be exactly `DYNAMODB_TABLE_NAME`:

```bash
aws elasticbeanstalk update-environment \
  --environment-name eb-java-app-prod \
  --option-settings \
    Namespace=aws:elasticbeanstalk:application:environment,OptionName=DYNAMODB_TABLE_NAME,Value=eb-app-heartbeat \
    Namespace=aws:elasticbeanstalk:application:environment,OptionName=AWS_REGION,Value=eu-north-1 \
  --profile admin --region eu-north-1
```

### 1.5 Create the IAM role GitHub Actions assumes (OIDC - no static keys)

Register GitHub as an OIDC identity provider (one-time per account):

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 \
  --profile admin
```

Create the role with a trust policy scoped to this repo. The `sub` claim uses
the repository identifier `1MuhireDavid/aws-labs` - not the browser URL, and
GitHub usernames are case-sensitive:

```bash
cat > trust-policy.json << 'JSON'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::047719661196:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike": { "token.actions.githubusercontent.com:sub": "repo:1MuhireDavid/aws-labs:ref:refs/heads/main" }
    }
  }]
}
JSON

aws iam create-role \
  --role-name github-actions-eb-deploy \
  --assume-role-policy-document file://trust-policy.json \
  --profile admin
```

Attach a least-privilege permissions policy: scoped S3 access to the two
buckets used, EB deploy actions, and the read-only Describe calls EB performs
during a deployment:

```bash
cat > deploy-policy.json << 'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SourceBundleBucketAccess",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:GetObjectAcl"],
      "Resource": "arn:aws:s3:::eb-java-app-source-047719661196/*"
    },
    {
      "Sid": "ElasticBeanstalkStorageBucket",
      "Effect": "Allow",
      "Action": ["s3:CreateBucket", "s3:GetBucketLocation", "s3:GetBucketPolicy", "s3:PutBucketPolicy", "s3:ListBucket"],
      "Resource": "arn:aws:s3:::elasticbeanstalk-eu-north-1-047719661196"
    },
    {
      "Sid": "ElasticBeanstalkStorageObjects",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:GetObjectAcl", "s3:PutObjectAcl", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::elasticbeanstalk-eu-north-1-047719661196/*"
    },
    {
      "Sid": "ElasticBeanstalkDeploy",
      "Effect": "Allow",
      "Action": [
        "elasticbeanstalk:CreateApplicationVersion",
        "elasticbeanstalk:UpdateEnvironment",
        "elasticbeanstalk:DescribeEnvironments",
        "elasticbeanstalk:DescribeApplicationVersions",
        "elasticbeanstalk:DescribeEvents",
        "elasticbeanstalk:DescribeConfigurationSettings"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ElasticBeanstalkSupportingReads",
      "Effect": "Allow",
      "Action": [
        "autoscaling:DescribeAutoScalingGroups",
        "autoscaling:DescribeScalingActivities",
        "autoscaling:DescribeLaunchConfigurations",
        "cloudformation:DescribeStacks",
        "cloudformation:DescribeStackResources",
        "cloudformation:DescribeStackResource",
        "cloudformation:GetTemplate",
        "ec2:DescribeImages",
        "ec2:DescribeInstances",
        "ec2:DescribeLaunchTemplates",
        "ec2:DescribeSecurityGroups",
        "ec2:DescribeSubnets",
        "ec2:DescribeVpcs",
        "ec2:DescribeKeyPairs",
        "ec2:DescribeAvailabilityZones",
        "ec2:DescribeAccountAttributes",
        "elasticloadbalancing:DescribeLoadBalancers",
        "elasticloadbalancing:DescribeTargetGroups",
        "elasticloadbalancing:DescribeTargetHealth"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ElasticBeanstalkStackUpdate",
      "Effect": "Allow",
      "Action": ["cloudformation:UpdateStack"],
      "Resource": "arn:aws:cloudformation:eu-north-1:047719661196:stack/awseb-*/*"
    }
  ]
}
JSON

aws iam put-role-policy \
  --role-name github-actions-eb-deploy \
  --policy-name eb-deploy-least-privilege \
  --policy-document file://deploy-policy.json \
  --profile admin
```

Because the source bundle bucket has a custom name (not the auto-created
`elasticbeanstalk-*` name), the EB service also needs a bucket policy allowing
it to read source bundles:

```bash
cat > bucket-policy.json << 'JSON'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "AllowElasticBeanstalkServiceAccess",
    "Effect": "Allow",
    "Principal": { "Service": "elasticbeanstalk.amazonaws.com" },
    "Action": ["s3:GetObject", "s3:GetObjectVersion", "s3:ListBucket"],
    "Resource": [
      "arn:aws:s3:::eb-java-app-source-047719661196",
      "arn:aws:s3:::eb-java-app-source-047719661196/*"
    ],
    "Condition": { "StringEquals": { "aws:SourceAccount": "047719661196" } }
  }]
}
JSON

aws s3api put-bucket-policy \
  --bucket eb-java-app-source-047719661196 \
  --policy file://bucket-policy.json --profile admin
```

Get the role ARN for the GitHub secret in the next section:

```bash
aws iam get-role --role-name github-actions-eb-deploy \
  --profile admin --query "Role.Arn" --output text
```

---

## 2. GitHub repository configuration

In the repository **Settings -> Secrets and variables -> Actions**, at the
**repository** level (not environment-scoped), split as follows.

**Secrets tab:**

| Name | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::047719661196:role/github-actions-eb-deploy` |

**Variables tab:**

| Name | Value |
|---|---|
| `AWS_REGION` | `eu-north-1` |
| `EB_SOURCE_BUCKET` | `eb-java-app-source-047719661196` |
| `EB_APPLICATION_NAME` | `eb-java-app` |
| `EB_ENVIRONMENT_NAME` | `eb-java-app-prod` |

The role ARN is a secret (read via `secrets.`); the other four are plain
variables (read via `vars.`). No AWS access keys are ever stored - the
workflow exchanges a short-lived OIDC token for the role above.

---

## 3. Day-to-day workflow

1. Make a change under `eb-java-app/`, commit, `git push origin main`.
2. GitHub Actions automatically builds, packages, uploads to S3, creates a new
   EB application version, deploys it, waits for green, and curls `/version`.
3. The EB console **Application versions** tab shows the growing, timestamped
   version history. Each label is `v<run_number>.<attempt>-<commit>`.
4. Visit `/version` to confirm the new commit hash and build time.

## 4. Live review checklist

- Open the EB URL -> JSON confirmation payload
- Make a small visible change (edit the message string), push to `main`
- Show the GitHub Actions run completing end to end
- Refresh `/version` -> new commit hash / build time appears
- EB console -> Application versions -> version history
- Hit `/db-check` -> `"status": "CONNECTED"` with a live DynamoDB write

## 5. Rollback

Every deployment is an immutable, tracked application version, so rolling back
is a single command with no rebuild:

```bash
aws elasticbeanstalk update-environment \
  --environment-name eb-java-app-prod \
  --version-label <previous-version-label> \
  --profile admin --region eu-north-1
```