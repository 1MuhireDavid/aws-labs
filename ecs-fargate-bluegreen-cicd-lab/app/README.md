# app/ — Application for the ECS Blue/Green Lab

A minimal Spring Boot app whose only job is to render the landing page
required by the lab (your full name + the lab name), plus enough
production-shaped scaffolding (health endpoint, container metadata,
version stamp) to make a blue/green deployment visibly verifiable.

## Layout

```
src/main/java/com/labs/ecsdemo/     # Spring Boot app
src/main/resources/
  application.yaml                  # reads APP_OWNER_NAME/LAB_NAME/APP_VERSION
  templates/index.html              # the landing page
Dockerfile                          # multi-stage build, non-root runtime user
ecs/
  taskdef.json                      # CodeDeploy task definition template
  appspec.yaml                      # CodeDeploy appspec template
```
(the GitHub Actions workflow that builds/pushes this app lives at the
repo root, `.github/workflows/ecs-bluegreen-lab-app-build-and-push.yml`
— GitHub requires that path regardless of monorepo layout — and is
path-filtered to only trigger on changes under this folder)

## Run locally

```bash
cd ecs-fargate-bluegreen-cicd-lab/app
mvn spring-boot:run
# or
docker build -t ecsdemo --build-arg APP_VERSION=local .
docker run -p 8080:8080 -e APP_OWNER_NAME="Jane Doe" ecsdemo
curl localhost:8080
```

## Image tagging strategy: consistent + mutable

Every successful build on `main` (that touches this folder) pushes
**two tags** to the same image:

| Tag | Mutability | Purpose |
|---|---|---|
| `sha-<12-char-git-sha>` | immutable identity | traceability, manual rollback target |
| `latest` | **mutable** | the one tag EventBridge and the CodePipeline ECR source action watch — always "the newest thing on `main`" |

This only works because the ECR repository itself is created with
`ImageTagMutability: MUTABLE` (`../infra/cfn/modules/03-ecr-endpoints.yaml`);
otherwise re-pushing `:latest` on every build would be rejected.

## Why a bootstrap placeholder image

The ECS service, ALB target groups, and CodeDeploy all get created by
CloudFormation the very first time the infra stack runs — before this
app's GitHub Action has ever pushed a real image. To avoid a chicken-
and-egg failure (`CREATE_COMPLETE` blocked on an image that doesn't
exist), the task definition's `InitialImageTag` parameter defaults to
the sentinel value `bootstrap`, which the infra template swaps for a
public `nginx` image (remapped to listen on the same container port) —
see `../infra/cfn/modules/04-alb-ecs.yaml`. The ALB health check path is
`/` for exactly this reason: it's the one path both nginx and this app
answer with `200`. Once you push a real image and CodeDeploy runs its
first blue/green release, CodeDeploy — not this parameter — owns the
running task definition from then on.

## One-time setup for `ecs/taskdef.json`

`taskdef.json` is a template, but CodePipeline's `CodeDeployToECS` action
only substitutes the `IMAGE1_NAME` placeholder automatically. The IAM
role ARNs, account ID, and region are stable values, so fill them in once
by editing the file directly — no CLI needed:

1. Open `ecs/taskdef.json` in GitHub's web editor (navigate to the file
   in the repo, click the pencil ✏️ icon) or any local text editor.
2. Replace the three placeholders:
   - `<AWS_ACCOUNT_ID>` — find it in the **AWS Console**: click your
     account name in the top-right corner; your 12-digit Account ID is
     shown in that menu (also on the **Account** page under your name).
   - `<AWS_REGION>` — the region shown in the Console's top-right region
     selector (whichever region you deployed the infra stack into).
   - `<YOUR_FULL_NAME>` — your name, exactly as you want it displayed.
3. Commit directly to `main` (via the GitHub web UI's **Commit changes**
   button, or a normal `git commit` + `git push` if you're working
   locally).

`ecs/appspec.yaml`'s `<TASK_DEFINITION>` placeholder is different: it's a
**literal string** CodeDeploy itself substitutes at deploy time with the
ARN of the task definition revision it just registered — leave it as-is.

## Required GitHub repo secrets

Same secrets store as the infra workflow (one repo) — added via the
GitHub Console in `../infra/README.md` step 2, using values read from
the CloudFormation Console's Outputs tab on the bootstrap stack:

| Secret | Example |
|---|---|
| `AWS_ECR_PUSH_ROLE_ARN` | `arn:aws:iam::123456789012:role/ecs-bluegreen-lab-gha-ecr-push-role` |
| `AWS_REGION` | `us-east-1` |
| `ECR_REPOSITORY` | `ecs-bluegreen-lab-app` |

## What happens on push to `main` under this folder

(any change under this folder — including this README — satisfies the
workflow's path filter and triggers the steps below)

1. `ecs-bluegreen-lab-app-build-and-push.yml` assumes
   `AWS_ECR_PUSH_ROLE_ARN` via **OIDC** — a role that (via the
   `job_workflow_ref` trust condition) only this exact workflow file can
   assume, even though it shares a repo with the infra workflow.
2. Builds the image, tags it `sha-<sha>` and `latest`, pushes both.
3. The `:latest` push fires an `ECR Image Action` event → the
   `EcrPushRule` EventBridge rule in the infra stack → starts
   `ecs-bluegreen-lab-pipeline`.
4. CodePipeline reads the new image URI (ECR source action) and this
   folder's `ecs/appspec.yaml` + `ecs/taskdef.json` (GitHub source
   action, `DetectChanges: false` so it never self-triggers on unrelated
   monorepo commits), hands both to CodeDeploy.
5. CodeDeploy registers a new task definition revision, spins up "green"
   tasks, waits for them to pass the ALB health check, shifts the
   listener's traffic from "blue" to "green", then terminates the old
   "blue" tasks.
