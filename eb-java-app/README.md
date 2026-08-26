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
- **Platform:** `64bit Amazon Linux 2023 v4.12.7 running Corretto 17`
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

## 1. One-time AWS setup (CloudFormation Git sync)

Everything AWS-side - the S3 bucket, the DynamoDB table, the EC2 instance
role, the GitHub OIDC provider and deploy role, and the Elastic Beanstalk
application/environment - is provisioned by a single stack defined in
[`infrastructure.yaml`](infrastructure.yaml). This stack is connected to
this repository via **CloudFormation Git sync**, so after the one-time setup
below there is no manual template upload, no `aws cloudformation deploy`,
and no console click-through for any future infrastructure change - editing
`infrastructure.yaml` and pushing to `main` is the entire deploy process.

### 1.1 One-time: connect CloudFormation to this repo

In the CloudFormation console, create a stack using **"Sync from Git"**
(not "Upload a template file"):

1. **Stack name:** `eb-java-app`.
2. **Stack deployment file:** choose *"Create the file using the following
   parameters and place it in my repository"* - this has CloudFormation
   generate and commit the deployment file for you, instead of hand-writing
   one (which would just be a different kind of manual step).
3. **Template file path:** `eb-java-app/infrastructure.yaml`.
4. **Parameters:** fill in `SourceBucketName` (e.g.
   `eb-java-app-source-<your-account-id>` - required, has no default) and
   `CreateOidcProvider` (`true` only if this is the first stack creating the
   GitHub OIDC provider in the account; otherwise `false` - see the
   parameter's description in the template). Leave the rest blank to use
   the template's own defaults (`ApplicationName`, `EnvironmentName`,
   `DynamoTableName`, `GitHubOrg`, `GitHubRepo`, `SolutionStackName`).
   `SolutionStackName` must be an exact, currently-available platform
   string - CloudFormation has no "latest" keyword for it, and AWS retires
   old versions over time (this is what caused the `CREATE_FAILED` on
   `BeanstalkEnvironment` if you hit "No Solution Stack named ... found").
   Confirm the current one with
   `aws elasticbeanstalk list-available-solution-stacks --region eu-north-1 --query "SolutionStacks[?contains(@, 'Corretto 17')]"`
   before deploying if it's been a while. The environment has Managed
   Platform Updates enabled (`aws:elasticbeanstalk:managedactions`), so once
   it's running, patch/minor platform bumps apply automatically - this pin
   should only need re-checking if you're doing a fresh `CREATE`.
5. **Template definition repository:** repository `aws-labs`, branch
   `main` (the linked repo/branch this stack tracks going forward).
6. **Deployment file path:** where CloudFormation commits the generated
   deployment file, e.g. `eb-java-app/deployment-file.yaml`.
7. **IAM role:** *"Create default role"* - a new role CloudFormation itself
   uses to read the repo and apply changes (distinct from `InstanceRole`,
   `ServiceRole`, and `GitHubDeployRole`, which are for the running app and
   for GitHub Actions - this role is for CloudFormation's own sync engine).
8. **Enable comment on pull request:** optional - posts a predicted-changes
   summary on any PR touching the template, before merge.
9. Acknowledge IAM capability creation (same "CloudFormation might create
   IAM resources with custom names" acknowledgment as a normal stack
   create - required because the template names its roles explicitly), then
   create the stack.

This is a one-time bootstrap action, the same way `git init` is one-time.
Stack creation itself takes roughly 5-10 minutes - most of that is Elastic
Beanstalk provisioning the load balancer, auto scaling group, and EC2
instances underneath the environment.

Because `aws-labs` is a multi-lab monorepo, this sync only reacts to
changes to the two tracked paths (`eb-java-app/infrastructure.yaml` and the
deployment file) - pushes touching other labs (`ecr-oidc-lab/`,
`auto-scaling-lab/`, etc.) never trigger this stack.

### 1.2 Making future infrastructure changes

Never touch the CloudFormation console or CLI to deploy again. To change
anything about the infrastructure:

- **Change a resource** (add/edit something in the template) -> edit
  `infrastructure.yaml`, commit, `git push origin main`. CloudFormation
  detects the change and applies it automatically.
- **Change a parameter value** (e.g. rotate to a new `SourceBucketName`, or
  flip `CreateOidcProvider`) -> edit the generated deployment file
  (`eb-java-app/deployment-file.yaml`) directly in the repo, commit, push.
  Same automatic apply.

### 1.3 Read the stack outputs

Console: CloudFormation -> your stack -> **Outputs** tab (this is just
*reading* a value, not deploying anything, so it's fine to check here).

CLI, if you prefer:

```bash
aws cloudformation describe-stacks \
  --stack-name eb-java-app \
  --query "Stacks[0].Outputs" \
  --profile admin --region eu-north-1
```

You'll use these three outputs (`DeployRoleArn`, `SourceBucket`,
`EnvironmentName`) to fill in the GitHub repository configuration in the
next section.

### 1.4 About the "initial deployment"

The template creates the Elastic Beanstalk environment without a
`VersionLabel`, so it boots with AWS's built-in placeholder ("Sample
Application") - no manual jar build/zip/upload is needed just to stand the
environment up. The **first real deployment** happens automatically the
first time you push to `main`: GitHub Actions builds the jar, uploads it to
the `SourceBucket` the stack created, and calls
`create-application-version` / `update-environment`, replacing the sample
app. This still satisfies the lab requirement that the initial deployment
"must use a source bundle stored in Amazon S3 and be deployed via Elastic
Beanstalk (not directly from GitHub)" - CloudFormation prepares the
destination and permissions; the GitHub Actions workflow performs the
actual first deploy through S3, same as every deploy after it.

The DynamoDB table (`HeartbeatTable`), the EC2 instance role's permission
to read/write it, and the `DYNAMODB_TABLE_NAME` / `AWS_REGION` environment
variables on the environment are all created by the stack too - nothing
further to configure for the external-service integration.

---

## 2. GitHub repository configuration

Using the stack outputs from step 1.3 (and the parameter values you chose
when deploying), fill these in under repository **Settings -> Secrets and
variables -> Actions**, at the **repository** level (not
environment-scoped). Example values below assume the defaults in
`infrastructure.yaml` and account `047719661196` - substitute your own.

**Secrets tab:**

| Name | Value | Source |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::047719661196:role/github-actions-eb-deploy-eb-java-app-prod` | Stack output `DeployRoleArn` |

**Variables tab:**

| Name | Value | Source |
|---|---|---|
| `AWS_REGION` | `eu-north-1` | The `--region` you deployed the stack to |
| `EB_SOURCE_BUCKET` | `eb-java-app-source-047719661196` | Stack output `SourceBucket` |
| `EB_APPLICATION_NAME` | `eb-java-app` | The `ApplicationName` parameter you used |
| `EB_ENVIRONMENT_NAME` | `eb-java-app-prod` | Stack output `EnvironmentName` |

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

## 6. Tear down

Git sync automates *deploys* (push a change, it applies) - it does not
automate *deletion*. Tearing the stack down is a deliberate, one-off action
you still take through the console or CLI, same as unplugging any other
running system:

```bash
aws cloudformation delete-stack --stack-name eb-java-app \
  --profile admin --region eu-north-1
```

Two things CloudFormation can't do for you here:

- **Empty the source bucket first.** `SourceBucket` has versioning enabled,
  so it accumulates object versions over time; CloudFormation refuses to
  delete a non-empty bucket and the stack deletion will fail/roll back on
  that resource. Empty it before deleting the stack:
  `aws s3 rm s3://<your-source-bucket-name> --recursive --profile admin`
  (or, in the console, select the bucket -> **Empty**).
- **The GitHub OIDC provider is shared.** If you deployed with
  `CreateOidcProvider=true`, deleting this stack removes the
  `token.actions.githubusercontent.com` provider too - which will break
  *any other* stack/role in this account (e.g. `ecr-oidc-lab`) that also
  trusts it. Only delete this stack with that flag on if you're sure
  nothing else in the account depends on the provider.