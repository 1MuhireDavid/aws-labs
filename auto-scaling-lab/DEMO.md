# Live Demo Runbook

Use this script during the stakeholder review. It covers every rubric item
under "Validation and Demonstration."

Set these once, at the top of your terminal session:

```bash
STACK=autoscaling-lab

ALB_URL=$(aws cloudformation describe-stacks --stack-name $STACK \
  --query "Stacks[0].Outputs[?OutputKey=='ALBEndpoint'].OutputValue" --output text)

ASG_NAME=$(aws cloudformation describe-stacks --stack-name $STACK \
  --query "Stacks[0].Outputs[?OutputKey=='AutoScalingGroupName'].OutputValue" --output text)

TG_ARN=$(aws cloudformation describe-stacks --stack-name $STACK \
  --query "Stacks[0].Outputs[?OutputKey=='TargetGroupArn'].OutputValue" --output text)

echo "ALB URL: $ALB_URL"
echo "ASG:     $ASG_NAME"
```

---

## 1. Access the application via the single public endpoint

```bash
curl -s $ALB_URL | grep -E "Instance ID|Availability Zone"
```

Open `$ALB_URL` in a browser too — the page auto-refreshes every 5 seconds
(`<meta http-equiv="refresh" content="5">`), so stakeholders watching the
screen will see the Instance ID/AZ update live as the ALB rotates targets.

## 2. Prove round-robin distribution across instances

With `MinSize=1`, only one instance exists at first — bump desired capacity
to 2 so there's something to distribute across (or just wait for the
stress-test scale-out in step 4).

```bash
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name $ASG_NAME \
  --desired-capacity 2 --honor-cooldown

# Wait ~60-90s for the new instance to pass health checks, then:
for i in $(seq 1 10); do
  curl -s $ALB_URL | grep "Instance ID"
done
```

You should see the Instance ID alternate between two different values —
direct evidence of round-robin distribution.

## 3. Confirm targets are registered and healthy

```bash
aws elbv2 describe-target-health --target-group-arn $TG_ARN \
  --query "TargetHealthDescriptions[].{Instance:Target.Id,State:TargetHealth.State}" \
  --output table
```

## 4. Trigger a real CPU stress test (no SSH required)

Instances have **no SSH access**. Connect via **SSM Session Manager**
instead — this uses the `AmazonSSMManagedInstanceCore` role already attached
to the instance profile.

```bash
# Pick any InService instance ID from the ASG
INSTANCE_ID=$(aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names $ASG_NAME \
  --query "AutoScalingGroups[0].Instances[0].InstanceId" --output text)

aws ssm start-session --target $INSTANCE_ID
```

Once connected (in the SSM shell on the instance):

```bash
# stress-ng was installed via User Data at launch
sudo stress-ng --cpu $(nproc) --cpu-load 95 --timeout 300s &
```

Leave that running and exit the session (`exit`) — the stress process
continues in the background on the instance.

## 5. Watch CPU climb and the scale-out event fire

```bash
# CloudWatch metric, refreshed every minute
aws cloudwatch get-metric-statistics \
  --namespace AWS/EC2 --metric-name CPUUtilization \
  --dimensions Name=AutoScalingGroupName,Value=$ASG_NAME \
  --start-time $(date -u -d '10 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 --statistics Average --output table

# Watch the ASG add capacity in real time
watch -n 15 "aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names $ASG_NAME \
  --query 'AutoScalingGroups[0].Instances[].{ID:InstanceId,State:LifecycleState,Health:HealthStatus}' \
  --output table"
```

Once average CPU exceeds the 30% target, the `CpuTargetTrackingPolicy`
target-tracking policy raises desired capacity automatically (visible as a
new `Pending` → `InService` instance, up to `MaxSize=4`).

You can also just narrate this live from the console: **EC2 → Auto Scaling
Groups → autoscaling-lab-ASG → Activity tab** shows each scaling activity
with a timestamp and cause (e.g. *"triggered by alarm... breached threshold"*
style target-tracking event).

## 6. Confirm new instances register with the target group automatically

Re-run the command from step 3 — the new instance(s) should appear
`healthy` within ~60–90 seconds of launching (health check interval is 15s,
2 consecutive successes required):

```bash
aws elbv2 describe-target-health --target-group-arn $TG_ARN \
  --query "TargetHealthDescriptions[].{Instance:Target.Id,State:TargetHealth.State}" \
  --output table
```

Refresh the browser tab pointed at `$ALB_URL` a few more times — you should
now see 2–4 distinct Instance IDs rotating through.

## 7. (Extra credit) Observe scale-in after load stops

Once the 300s `stress-ng` timeout expires, average CPU drops back below the
30% target. The same target-tracking policy scales the ASG back down toward
`MinSize=1` after its cooldown window — no separate scale-in policy needed.

```bash
watch -n 30 "aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names $ASG_NAME \
  --query 'AutoScalingGroups[0].[DesiredCapacity,MinSize,MaxSize]' --output text"
```

## 8. Clean up after the demo

```bash
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name $ASG_NAME --desired-capacity 1
```

(Full stack teardown instructions are in the main `README.md`.)
