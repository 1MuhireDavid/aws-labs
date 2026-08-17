# infra/ — CloudFormation for the ECS Blue/Green Lab

This folder is managed by **CloudFormation Git sync** — pushes to `main`
under this path are what actually change the AWS environment. The one
deliberate exception is the bootstrap stack, deployed once via the
**AWS Console** (see below) rather than through Git sync.

## Layout

```
cfn/
  bootstrap/00-bootstrap.yaml   # deployed ONCE, manually (see below)
  modules/
    01-network.yaml             # VPC, public+private subnets x2 AZ, NAT
    02-security.yaml             # least-privilege security groups
    03-ecr-endpoints.yaml         # ECR repo + interface/gateway VPC endpoints
    04-alb-ecs.yaml                 # ALB, ECS cluster/service, autoscaling
    05-cicd-pipeline.yaml            # CodePipeline, CodeDeploy, EventBridge
  root.yaml                      # master template — what Git sync deploys
  deployment-file.yaml           # Git sync's parameters/tags file
scripts/bootstrap.sh             # one-time bootstrap helper
```
(the two GitHub Actions workflows that operate on this folder live at the
repo root, `.github/workflows/ecs-bluegreen-lab-*.yml` — GitHub requires
that path regardless of monorepo layout)

## Why nested stacks are hosted in S3

`root.yaml` references each module via `TemplateURL` pointing at S3
(required by nested stacks, and explicitly requested by the lab spec).
Git sync deploys `root.yaml` **as-is** — it does not run `aws
cloudformation package`, so there's no automatic step that uploads
`cfn/modules/*.yaml` anywhere. This repo closes that gap itself:

1. `.github/workflows/ecs-bluegreen-lab-infra-package-templates.yml` runs
   on every push that touches this lab's `cfn/modules/**`. It authenticates
   to AWS **via OIDC** (no long-lived secrets) and uploads the modules to
   `s3://<bucket>/templates/<git-short-sha>/`.
2. It then bumps `TemplatesVersion` in `deployment-file.yaml` to that
   same short SHA and commits the change back to `main`.
3. That commit is what Git sync actually watches — so a
   nested-template-only change still produces a real parameter change
   on `root.yaml`, guaranteeing CloudFormation re-resolves the child
   templates (rather than relying on it noticing an S3 object changed
   under an unchanged URL).

## Git sync vs. GitHub Actions OIDC — two different auth mechanisms, on purpose

The lab requires "all CI/CD interactions with AWS use OIDC." Two
different systems touch AWS here, and they use two different (both
credential-free, both keyless) mechanisms appropriate to what's calling:

| System | Direction | Auth mechanism |
|---|---|---|
| GitHub Actions (both `ecs-bluegreen-lab-*.yml` workflows) | GitHub → AWS | **OIDC** federated role (`sts:AssumeRoleWithWebIdentity`), scoped per-workflow-file, no stored secrets |
| CloudFormation Git sync | AWS → GitHub (AWS reads your repo to deploy it) | AWS's native **CodeConnections** (GitHub App install) — this is AWS pulling from GitHub, not GitHub calling AWS, so it isn't an OIDC-federation scenario at all |

Both are secretless/keyless from GitHub's side; only the first is
literally "OIDC" in the IAM sense, since OIDC federation only makes
sense for the direction where GitHub Actions is the caller.

## One repo, two workflows, still isolated

Both workflows live in the same `aws-labs` repo. Repo-level OIDC trust
(`repo:org/repo:ref:refs/heads/main`) alone would let either workflow
assume either role. `00-bootstrap.yaml` additionally checks GitHub's
`job_workflow_ref` claim on each role's trust policy, so:

- `ecs-bluegreen-lab-gha-infra-packaging-role` can only be assumed by
  `ecs-bluegreen-lab-infra-package-templates.yml`.
- `ecs-bluegreen-lab-gha-ecr-push-role` can only be assumed by
  `ecs-bluegreen-lab-app-build-and-push.yml`.

Path filters on the workflows (`on.push.paths`) additionally keep them
from firing on other labs' commits, and vice versa.

## One-time bootstrap (the deliberate exception to "Git sync does everything")

`cfn/bootstrap/00-bootstrap.yaml` creates the S3 templates bucket, the
`token.actions.githubusercontent.com` OIDC provider (a **singleton per
AWS account** — set `CreateOidcProvider` to `false` below if a different
lab in this same repo already made one), and the two scoped OIDC roles.
It is deployed once, manually — **not** through Git sync — because an
OIDC provider must never be at risk of being deleted/recreated by a
routine app or infra change.

**Deploy it via the AWS Console** (no CLI needed):

1. Sign in to the **AWS Console** and pick your target Region in the
   top-right region selector — everything else in this lab deploys into
   whatever region you pick here, so note it down.
2. *(Skip this check if this is the first lab in the account to use
   GitHub OIDC.)* Go to **IAM → Identity providers**. If
   `token.actions.githubusercontent.com` is already listed, you'll set
   `CreateOidcProvider` to `false` in step 5.
3. Go to **CloudFormation → Stacks → Create stack → With new resources
   (standard)**.
4. Under **Specify template**, choose **Upload a template file → Choose
   file**, and select
   `ecs-fargate-bluegreen-cicd-lab/infra/cfn/bootstrap/00-bootstrap.yaml`
   from your local clone of the repo. Click **Next**.
5. **Stack name:** `ecs-bluegreen-lab-bootstrap`. Fill in the parameters:

   | Parameter | Value |
   |---|---|
   | ProjectName | `ecs-bluegreen-lab` (default) |
   | GitHubOrg | `1MuhireDavid` |
   | RepoName | `aws-labs` (default) |
   | AllowedGitRef | `refs/heads/main` (default) |
   | InfraPackagingWorkflowFile | leave default unless you renamed the workflow file |
   | AppBuildWorkflowFile | leave default unless you renamed the workflow file |
   | CreateOidcProvider | `true`, unless step 2 found an existing provider — then `false` |

   Click **Next**.
6. **Configure stack options:** leave everything at its default (add
   tags here if your account requires them). Click **Next**.
7. On the **Review** page, scroll to the **Capabilities** box at the
   bottom and check **"I acknowledge that AWS CloudFormation might
   create IAM resources with custom names."** This is required because
   the template creates two named IAM roles. Click **Submit**.
8. Wait for the stack status to reach **CREATE_COMPLETE** (this stack is
   just S3 + IAM, so it typically takes under two minutes — refresh with
   the circular arrow icon if needed).
9. Click into the stack and open its **Outputs** tab. You'll need three
   values from here in the next step: `TemplatesBucketName`,
   `InfraPackagingRoleArn`, and `AppEcrPushRoleArn`.

*(Prefer the CLI? `scripts/bootstrap.sh` does the same thing and prints
the same output values — either path is fine, they create identical
resources.)*

## Full setup order

1. **Deploy the bootstrap stack via the Console** — see above. Note the
   three Output values.
2. **Add repository secrets** (GitHub's UI, not AWS): go to
   `github.com/1MuhireDavid/aws-labs` → **Settings → Secrets and
   variables → Actions → New repository secret**, and add each of these
   one at a time:

   | Secret name | Value |
   |---|---|
   | `AWS_INFRA_PACKAGING_ROLE_ARN` | the `InfraPackagingRoleArn` output |
   | `AWS_TEMPLATES_BUCKET` | the `TemplatesBucketName` output |
   | `AWS_ECR_PUSH_ROLE_ARN` | the `AppEcrPushRoleArn` output |
   | `AWS_REGION` | the region you deployed into (step 1) |
   | `ECR_REPOSITORY` | `ecs-bluegreen-lab-app` |

3. **Fill in `cfn/deployment-file.yaml`** — open the file (locally, or
   with GitHub's web editor: navigate to the file in the repo and click
   the pencil ✏️ icon) and replace the two placeholders:
   - `TemplatesBucketName:` → the `TemplatesBucketName` output value
   - `AppOwnerName:` → your full name

   `GitHubOrg` and `RepoName` are already filled in for this account.
   Commit the change.
4. **Push this repo to GitHub on `main`** (a normal `git push`, or edit
   files directly in the GitHub web UI and commit to `main`). The
   `ecs-bluegreen-lab-infra-package-templates.yml` workflow runs
   automatically (path-filtered, so it only triggers on this lab's
   changes), uploads the nested templates to S3, and commits the first
   `TemplatesVersion` back to the repo — no action needed from you here.
5. **Turn on Git sync**, entirely in the CloudFormation console:
   - Go to **CloudFormation → Stacks → Create stack → With Git sync**
     (or open an existing stack → **Sync from Git**).
   - Connect to `1MuhireDavid/aws-labs`, branch `main`.
   - Deployment file path:
     `ecs-fargate-bluegreen-cicd-lab/infra/cfn/deployment-file.yaml`.
   - Accept the console's defaults for the Git sync service role and
     stack execution role, granting `CAPABILITY_NAMED_IAM`.
   - Git sync opens a pull request confirming the deployment file schema
     — merge it on GitHub (or click through if the console offers an
     in-place confirmation) to kick off the first deploy.
   - Watch the stack's **Events** tab in the CloudFormation console for
     progress; the full nested-stack deploy (VPC, NAT, ALB, ECS, pipeline)
     typically takes 10–15 minutes.
6. **Authorize the GitHub connection** — in the CloudFormation console,
   go to **Developer Tools → Settings → Connections**, find the
   connection created by `05-cicd-pipeline.yaml` (status **Pending**),
   click it, and click **Update pending connection** to complete the
   one-click GitHub App authorization. The pipeline's GitHub source
   action won't run until this is **Available**.
7. **Fill in `app/ecs/taskdef.json`'s placeholders** — see
   `../app/README.md` (three values you can find in the Console: your
   AWS account ID from the account menu top-right, the region from the
   region selector, and your name).
8. **Push a change under `ecs-fargate-bluegreen-cicd-lab/app/`** — its
   path-filtered GitHub Action builds/pushes the first real image,
   EventBridge fires, CodePipeline runs, CodeDeploy shifts traffic
   blue → green. Watch progress in the **CodePipeline** and
   **CodeDeploy** consoles.
9. **Open the app** — in the CloudFormation console, open the root stack
   (`ecs-bluegreen-lab`) → **Outputs** tab → `AlbEndpoint`.


## Security & cost notes

- Every security group is scoped to the single upstream SG that should
  reach it (Internet → ALB SG :80 → ECS task SG :container-port → VPC
  endpoint SG :443) — no `0.0.0.0/0` ingress below the ALB.
- ECS tasks run in **private** subnets with `AssignPublicIp: DISABLED`;
  all outbound calls that matter (ECR, CloudWatch Logs, STS) go over
  interface VPC endpoints, and the ECR image-layer store (S3) goes over a
  free gateway endpoint — NAT Gateway is present for other/edge-case
  internet egress (e.g. pulling the public bootstrap placeholder image)
  but ordinary steady-state traffic never touches it.
- `SingleNatGateway=true` by default (1 NAT Gateway) to keep the lab
  cheap; flip to `false` for fully-HA egress in a production setting.
- All S3 buckets: private, encrypted, versioned, deny-insecure-transport.
- IAM roles are purpose-scoped (e.g. the ECR-push role can only push to
  its own single ECR repository ARN — it cannot touch ECS, IAM, or any
  other repository — and is further scoped to one exact workflow file).
