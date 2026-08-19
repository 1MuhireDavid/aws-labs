# ecr-oidc-lab — Node.js → Docker → Amazon ECR via GitHub Actions OIDC

Part of the [`1MuhireDavid/aws-labs`](https://github.com/1MuhireDavid/aws-labs)
monorepo. Containerizes a small Node.js/Express app and automatically builds
+ pushes the image to a private Amazon ECR repository on every push that
touches this folder, authenticating to AWS with GitHub's native OIDC
provider — **no AWS access keys stored in GitHub, ever.**

See [`docs/architecture.md`](docs/architecture.md) for the full flow diagram
and a breakdown of every least-privilege control, including how the trust
policy is scoped down to this exact workflow file (important in a monorepo
where other labs share the same repo/branch).

## Layout

```
ecr-oidc-lab/
  app/                                 Node.js app + Dockerfile + .dockerignore
  infra/cloudformation/
    ecr-oidc-stack.yaml                The CloudFormation template
    ecr-oidc-stack.deploy.yaml          Git sync deployment file (parameters live here)
  infra/iam/                           Same trust/permissions policies as plain JSON,
                                        for reading/reference only
  docs/architecture.md                 Architecture diagram & security rationale

.github/workflows/ecr-oidc-lab-deploy.yml   <- lives at the REPO ROOT (GitHub
                                                requires this); path-filtered
                                                to only fire on ecr-oidc-lab/**
```

> Workflow files must live under `.github/workflows/` at the repo root —
> GitHub doesn't run workflows nested in subfolders. This one is named
> uniquely (`ecr-oidc-lab-deploy.yml`) and path-filtered so it stays out of
> the way of your other labs.

## One-time AWS setup — CloudFormation Git sync, zero CLI

Everything on the AWS side is provisioned through the CloudFormation
console's **Sync from Git** feature. No `aws` CLI commands, ever — you
click through the console once, and afterwards every commit to
`ecr-oidc-stack.yaml` or `ecr-oidc-stack.deploy.yaml` re-syncs the stack
automatically.

1. **Review the deployment file.** It's already filled in for this repo:
   [`infra/cloudformation/ecr-oidc-stack.deploy.yaml`](infra/cloudformation/ecr-oidc-stack.deploy.yaml)
   points at `GitHubOrg: 1MuhireDavid`, `GitHubRepo: aws-labs`. If you've
   already created the GitHub OIDC provider for a previous lab in this same
   AWS account, change `CreateOidcProvider` to `"false"` before continuing.

2. **AWS Console → CloudFormation → Create stack → With new resources.**
   Choose **"Template is ready"** and **"Sync from Git"**, then Next.

3. **Connect the repository** (first time only). Choose **Connect to
   GitHub** — this opens GitHub's own authorization screen for the "AWS
   Connector for GitHub" app. Select `1MuhireDavid/aws-labs` and complete
   the connection. If you've connected this repo for a previous lab
   already, just reuse that connection.

4. **Point at your files.** Branch `main`, template file path
   `ecr-oidc-lab/infra/cloudformation/ecr-oidc-stack.yaml`, and choose **"I
   am providing my own deployment file in my repository"** pointing to
   `ecr-oidc-lab/infra/cloudformation/ecr-oidc-stack.deploy.yaml`.

5. **Name the stack** (e.g. `ecr-oidc-lab`), acknowledge the checkbox that
   the template creates IAM resources (`CAPABILITY_NAMED_IAM`), and choose
   **Create stack**.

6. **Merge the pull request** CloudFormation opens in this repo — that
   merge is what actually triggers the first deployment.

7. **Copy the role ARN.** Once the stack shows `CREATE_COMPLETE`, open its
   **Outputs** tab and copy the `GitHubActionsRoleArn` value.

## One-time GitHub setup

`Settings → Secrets and variables → Actions → New repository secret`

| Name | Value |
|---|---|
| `AWS_ROLE_ARN` | the `GitHubActionsRoleArn` output copied above |

Since this is a shared secret across all labs in the repo, if a previous
lab already set `AWS_ROLE_ARN`, you'll need a different secret name (e.g.
`ECR_OIDC_LAB_ROLE_ARN`) and to update the `role-to-assume` line in
`.github/workflows/ecr-oidc-lab-deploy.yml` to match.

## Using it

Push a change under `ecr-oidc-lab/` (or run the workflow manually from the
Actions tab) and it will:

1. Check out the code
2. Assume the IAM role via OIDC — trust is checked against both the
   repo/branch and this exact workflow file
3. Log in to ECR
4. Build the image from `ecr-oidc-lab/app/Dockerfile`
5. Scan it with Trivy — fails the job (no push) on a CRITICAL CVE
6. Push it to ECR tagged `latest` and `<git-sha>`

Verify in the console: **AWS Console → ECR → Repositories →
ecr-oidc-lab → Images tab.**

## Local testing (optional, not part of the AWS deliverable)

```bash
cd ecr-oidc-lab/app
docker build -t ecr-oidc-lab:local .
docker run --rm -p 3000:3000 ecr-oidc-lab:local
curl http://localhost:3000/health
```

## Production hardening notes

The lab spec asks the workflow to trigger "on every push," so the trigger
is unfiltered by branch (any branch fires it) but is filtered by path to
this lab's folder — necessary in a shared repo. For a real production
pipeline you'd typically also:

- Restrict to `branches: [main]` and require PR review to merge
- Add a GitHub Environment with required reviewers for the deploy job
- Rotate/shorten `MaxSessionDuration` on the IAM role if sessions don't
  need the full hour
