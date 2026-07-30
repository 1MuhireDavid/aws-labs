# Auto Scaling Web Tier — AWS Reference Architecture

A highly available, auto-scaling web tier built with an Application Load
Balancer (ALB) and an EC2 Auto Scaling Group (ASG), fully defined as
Infrastructure as Code (IaC) in a single CloudFormation template.

## Architecture

```
                                Internet
                                   │
                                   ▼
                         ┌───────────────────┐
                         │   Internet Gateway │
                         └─────────┬──────────┘
                                   │
                 ┌─────────────────┴─────────────────┐
                 │                                    │
        ┌────────▼────────┐                 ┌────────▼────────┐
        │ Public Subnet AZ1│                 │ Public Subnet AZ2│
        │  (ALB + NAT GW)  │                 │      (ALB)       │
        └────────┬─────────┘                └────────┬─────────┘
                 │                                    │
                 └───────────────┬────────────────────┘
                                 │
                     ┌───────────▼────────────┐
                     │ Application Load Balancer│  (internet-facing,
                     │      round-robin         │   round-robin routing)
                     └───────────┬────────────┘
                                 │
                     ┌───────────▼────────────┐
                     │      Target Group        │
                     └───────────┬────────────┘
                                 │
                 ┌───────────────┴────────────────┐
                 │                                 │
        ┌────────▼────────┐               ┌────────▼────────┐
        │Private Subnet AZ1│               │Private Subnet AZ2│
        │   EC2 (ASG)      │◄─────NAT─────►│   EC2 (ASG)      │
        │  Apache + demo   │   Gateway      │  Apache + demo   │
        │  page, no SSH    │  (egress only) │  page, no SSH    │
        └──────────────────┘               └──────────────────┘

        Auto Scaling Group: Min 1 / Desired 1 / Max 4
        Target-tracking policy: keep average CPU ≈ 30%
```

### Design decisions & best practices applied

| Area | Decision | Why |
|---|---|---|
| Compute placement | EC2 instances live **only** in private subnets | No direct inbound access from the internet, per requirement |
| Public access | Single ALB in public subnets is the only internet-facing resource | Single public endpoint; instances stay hidden |
| Outbound internet | One **regional NAT Gateway** shared by both private subnets | Lets instances run `dnf update`/install packages while minimizing cost (one NAT vs. one-per-AZ) |
| SSH | No SSH security group rule at all; instance role has `AmazonSSMManagedInstanceCore` | Management via **SSM Session Manager** instead of SSH — no open port 22, no key pairs to manage/rotate |
| AMI selection | `AWS::SSM::Parameter::Value<AWS::EC2::Image::Id>` resolves the latest Amazon Linux 2023 AMI at deploy time | No hardcoded/stale AMI IDs |
| IMDS | `HttpTokens: required` (IMDSv2 enforced) | Mitigates SSRF-style credential theft |
| Storage | EBS root volume encrypted, gp3 | Encryption at rest, better price/performance than gp2 |
| Scaling | **Target-tracking** policy on `ASGAverageCPUUtilization` at 30% | Self-tuning: scales out *and* in automatically without hand-written CloudWatch alarms/step adjustments — fewer moving parts, less flapping |
| Health checks | ALB (`ELB`) health checks, not just EC2 status checks | An instance that's up but not serving traffic gets replaced |
| Cost | `t3.micro`, single NAT Gateway, Min=1 | Baseline cost stays minimal; capacity added only under real load |
| Tagging | `Name` tag propagated to every instance | Needed to visually tell instances apart during the demo |

## Repository contents

```
.
├── template.yaml        # The CloudFormation template (VPC → ASG, single file)
├── README.md            # This file
└── docs/
    └── DEMO.md           # Step-by-step live-demo / validation script
```

## Prerequisites

- An AWS account with permissions to create VPC, EC2, ELBv2, Auto Scaling,
  and IAM resources.
- AWS CLI v2 configured (`aws configure`) **or** access to the CloudFormation
  console.
- (For GitSync) A GitHub repository containing this project, and a
  CodeConnections connection from your AWS account to GitHub.

## Option A — Deploy with the AWS CLI (fastest for testing)

```bash
aws cloudformation deploy \
  --template-file template.yaml \
  --stack-name autoscaling-lab \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
      EnvironmentName=AutoScalingLab \
      InstanceType=t3.micro \
      MinSize=1 DesiredCapacity=1 MaxSize=4 CpuTargetValue=30
```

Get the public URL once it's done:

```bash
aws cloudformation describe-stacks \
  --stack-name autoscaling-lab \
  --query "Stacks[0].Outputs[?OutputKey=='ALBEndpoint'].OutputValue" \
  --output text
```

Open that URL in a browser — you'll see the instance ID, AZ, and private IP
of whichever instance served the request.

## Option B — Deploy via CloudFormation console (no CLI)

1. Console → **CloudFormation** → **Create stack** → *With new resources*.
2. Upload `template.yaml`.
3. Stack name: `autoscaling-lab`. Leave parameters at defaults (or adjust).
4. On the **Capabilities** step, acknowledge:
   *"I acknowledge that AWS CloudFormation might create IAM resources with
   custom names."*
5. Create stack, wait for `CREATE_COMPLETE` (~5–7 minutes — NAT Gateway
   creation is the slowest part).
6. Open the **Outputs** tab and copy `ALBEndpoint`.

## Option C — Deploy with CloudFormation GitSync (required deliverable)

GitSync keeps a stack continuously synced with a branch in your GitHub repo —
every push becomes a deployment, giving you full audit history.

1. **Push this project to a public GitHub repository.**
2. In the AWS Console, go to **CloudFormation → GitSync → Connect a Git
   repository**, or set it up directly from **Developer Tools → Settings →
   Connections** if you haven't linked GitHub before:
   - Choose **GitHub** as the provider and authorize AWS via the GitHub App
     (this creates a CodeConnections connection in `AVAILABLE` state).
3. Back in **CloudFormation → Stacks → Create stack → With Git sync**:
   - Select the connection created above.
   - Choose the repository and branch (e.g., `main`).
   - Template file path: `template.yaml`.
   - Deployment file: leave default (CloudFormation will offer to generate
     a `deployment-file.yaml` — accept the default; it just maps the stack
     name/region/parameters/capabilities to this template path).
   - Stack name: `autoscaling-lab`.
   - Capabilities: check `CAPABILITY_NAMED_IAM`.
4. Create the stack. CloudFormation commits a small deployment file back to
   your repo and provisions the stack.
5. From now on, **any push to the tracked branch** that changes
   `template.yaml` automatically triggers a new deployment — this is what
   satisfies "deployed using CloudFormation GitSync" and "repeatability
   through automated infrastructure provisioning."
6. Verify sync status and history any time under **CloudFormation → Stacks →
   autoscaling-lab → Git sync** tab.

## Cleaning up

```bash
aws cloudformation delete-stack --stack-name autoscaling-lab
```
(Or delete the stack from the console. If GitSync-managed, disconnect the
Git sync configuration first, then delete the stack.)

## Live demo / validation script

See [`docs/DEMO.md`](docs/DEMO.md) for the exact commands to:
- Prove round-robin load balancing across instances
- Trigger a real CPU stress test (no SSH — via SSM Session Manager)
- Watch the target-tracking policy scale out (and back in)
- Watch new instances register automatically with the target group
