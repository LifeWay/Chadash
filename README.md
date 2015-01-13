# Chadash
An immutable cloud deployer for Amazon.

#### What's in the name?
Chadash (khaw-dawsh') comes from the Hebrew word: **חָדָשׁ‎**

In the Strongs Greek and Hebrew dictionary, the definition for this word (Strongs #H2319) is:
> fresh, new thing

#### How is this different from Netflix's Asgard?

Chadash takes a different approach at launching new machines than Asgard does. Asgard works with AMI's as the source
for driving the rest of the process and it manages the creation of your ASG's, etc.

Chadash is very flexible because it drives around Cloud Formation templates allowing you to define in your templates the
specifics about your ASG's, Launch Configs, Instance creation, etc. It requires no data persistence, and we assume just
a few things about your cloud formation templates:
* Your template will create a new ASG for the deployment who's name is output as part of your template with the name "ChadashASG"
* Your ASG that is created in the template will be attached to one or more ELBs
* Your template will take two variables: ApplicationVersion (String) and ImageId (String - the AMI ID)

That's it! The rest is up to your control and design of your infrastructure in your cloud formation templates.

#### How it works
Chadash has a POST endpoint that you submit to request an application to start deploying. The endpoint has the stack file
name in the path. Chadash will then look for a cloud formation stack file in an S3 bucket you define. The stack files
must be in a folder within the bucket named: "chadash-asg-stacks".

For example, my "helloworld" stack: my-bucket-name/chadash-asg-stacks/helloworld.json

Once you have this setup, to deploy your stack hit the post endpoint with two variables. For example, if I was
trying to deploy a dev env of helloworld.json stack with an AMI of: ami-a4d7b3cc and version: 6.1, then I would have the
following post data:

POST: /api/deploy/helloworld
Content-Type: application/json

{
  "ami_id": "ami-a4d7b3cc",
  "version": "6.1"
}

The API would start the deployment process by first checking to see if any other previous helloworld stacks running. By
rule, only one stack can be running, and one other that is in the process of deploying for a given stackname / env at a
time. The deployment won't proceed if this check doesn't pass.

If there is no existing running stack, the process launches your stack for the first time.

If there is an existing running stack, then the blue / green process kicks in. Following the same idea as Asgard of
launching a new ASG along side the old one behind the same ELB(s), we first query the old stack's ASG, tell that ASG to
stop scaling activities based on alarms and schedules and then we launch the new stack. Once the new stack is done being
created, we'll have two ASG's attached to the same set of ELBs. We then tell the new stack's ASG that it also cannot
scale based on alarms and schedules and we tell it to grow to the same number of desired instances as the old stack's
ASG. During this time window, you will temporarily be running two times the number of instances than you actually need.

Once the new stack's ASG finishes launching the appropriate number of instances, we monitor that all of the ASG's
instances pass their health check in all of the ELB's to which they are attached. Once this check passes, we tell cloud
formation to delete the old stack.

The old stack will then be deleted, which cloud formation takes care of tearing down all of the associated machines,
launch configurations, and ASG's. Once the stack has reached delete_complete, we tell the new stack's ASG to
resume all scaling activities.

Your blue / green distributed deployment is complete.


#### More Details coming for the following:
* Monitoring a stack deployment with a websocket: /api/deploy/status-events/:stackName
* Basic Auth for restricting deployments for stacks to a given user
