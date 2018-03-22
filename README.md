[![Build Status](https://travis-ci.org/lifeway/Chadash.svg?branch=master)](https://travis-ci.org/lifeway/Chadash)

# Chadash
An immutable cloud deployer for AWS EC2 Instances. 

The goal of Chadash in one line of pseudo code (in Scala) is simply this:

``deploy(x: Version[B]) => Future[Either[Version[A], Version[B]]]``

#### What's in the name?
Chadash (khaw-dawsh') comes from the Hebrew word: **חָדָשׁ‎**

In the Strongs Greek and Hebrew dictionary, the definition for this word (Strongs #H2319) is:
> fresh, new thing

#### What is Chadash?
Chadash is an immutable deployment manager for AWS. It is designed to solve one problem: For a given application running behind a single ELB on EC2 instances, transition that application from version A to version B without an outage by launching new instances containing the next version of your application.

In a highly available architecture such as AWS, there is no such thing as "flip the switch" to migrate to your next version with zero down time. Such a deploy must be eventually consistent from version ``A => B``. Remember, when you use an ELB - the ELB itself is comprised of multiple load balancer machines with multiple IP addresses resolving under the DNS name for the load balancer. The load balancer machines that AWS manages for you that make up the ELB are eventually consistent with changes you make to the load balancer - including adding and removing new instances of your application to your ELB.

Perhaps the best way to describe how you would deploy the next version of your app, without mutating the current machines, and do it with zero downtime would be to first understand how you would do this by hand:

Assume I have an ELB running at myapp.mycompany.com. This ELB has an ASG attached to it with at least two EC2 instances in different AZ's for high availability. We will call this version A of 'myapp'. Now I want to deploy version B, but version A is under load and the ASG has scaled to running with 5 machines, and my business cannot tolerate even a minute of downtime. To handle this you launch a brand new ASG, setting the number of instances to 5, with the instances containing version B of 'myapp'. Next, you freeze the number of instances that version A is running so it won't scale down on you, and you then attach this new ASG to the same ELB that version A's ASG is already attached to. This will result in the user being able to hit both version A and version B at the same time (10 instances now in the ELB)- that is, once version B instances become healthy. Once all of the instances of version B are fully healthy, you detach version A's ASG from the ELB and shut all of those instances down, keeping the size at 5 instances for the new ASG and allowing it to scale up / down as needed.

Version B is now successfully deployed, and version A is offline, and you made this migration without taking your application offline while still handing load. Congrats! You have just manually done an immutable deployment (you didn't modify the currently running instances) behind an ELB with zero downtime. But you had to do it by hand. You want this process to repeat it self potentially hundreds of times a day, all the way from dev to prod across all of your applications using automation.

This is what Chadash is all about - managing this process for you, and doing it in a way that prevents a failure state from occurring. In the event Version B doesn't pass health checks after a given amount of time, we roll back to Version A (by terminating all of the version B instances) and stop the deployment. Version A was never affected during the entire process - it's still humming along just fine like nothing even happened (you didn't touch it, remember? It's immutable.)

#### Architecture
Chadash is written in Scala, using Akka to handle all of the business logic and Play Framework to handle the HTTP API requests along with the Websocket & Server-sent event messages so a user can monitor the state of the deployment.

Chadash tackles the problem of deploying on AWS by managing it with finite state machines. It takes the state of the system from one state to the next state, given that in the event of any error, Chadash takes the system back to the state it was in at the time of the deploy. Chadash heavily uses Akka FSM to model this concept. All AWS calls are modeled with basic actors with the FSM that requested the call acting as the supervisor. A supervisor strategy retries API calls that fail, and in the event the calls continue to fail, the supervisor reports failure back to the FSM and the FSM takes your application back to the original state.

Chadash requires no backing persistence store. Chadash uses semantic naming conventions for the things it creates. Additionally, Chadash utilizes as much of the AWS capabilities as it can. As such - all deployments themselves are actually Cloud Formation stacks. By using cloud formation, Chadash allows the engineer the greatest amount of flexibility and prevents Chadash from falling behind in capabilities. As AWS releases new features for Cloud Formation, you can use those in your deployments.

#### Assumptions
* You have a single ELB routing traffic to N number of instances.
* Those instances are bound only to that ELB. i.e. you're not modeling something complex where your instances might be bound to multiple ELBs.
* Those instances are launched with an Auto Scaling Group
* Each version you deploy shall have a unique version number
* The architecture of the application you are deploying can handle multiple versions of the application running behind the ELB at the same time (not a requirement of Chadash - but can lead to interesting side effects on your app if you don't understand that any zero downtime deployment that is distributed across multiple nodes MUST BE eventually consistent.)

If you can meet those assumptions, then you can use Chadash.

#### Cloudformation Setup
Chadash makes very few assumptions (see above) about your architecture. You model your application using cloud formation templates, where that template has only 3 requirements:
* Takes two input parameters: Version Number (of your app) and the AMI ID of the thing you are launching. For example: 

``` json
...
"Parameters" : {
    "ApplicationVersion" : {
      "Type" : "String",
      "Description" : "The version of your application - can be used within CloudInit, or only used to semantically create the AWS resource names"
    }, "ImageId" : {
      "Type" : "String",
      "Description" : "The EC2 image id to base the instances from"
    }
  }
...
```
  
* Somewhere in your cloud formation stack, you launch instances that bind to a previously named ELB - you can look this up with a custom lambda function inside of your Cloud Formation stack if you want! Your imagination is the limit.
* Your stack outputs the name of the autoscaling group that it created for your new instances. The name of this output parameter must be: ``ChadashASG``


You have a full world of possibilities with this kind of architecture. Perhaps you want to:
* create entirely new VPC's, subnets, security groups, etc unique for this deployment that can be thrown away when this version is replaced with a new version
* Run custom lambda functions that ``___________`` (fill in the blank).
* create new S3 buckets for this specific version.
* Setup new SNS topics or SQS topics just for this version of the app.
* Launch a new database for this version, and run some lambda function to pre-init what needs to be setup in the database (or migrated)
* Launch new ElasticCache instances for your new instances, and setup the security group rules between your instances and your elastic cache instances.

 As you can see, just about anything is possible. Just dream it up in CloudFormation and as long as you follow the requirements for what Chadash needs from your template, you should be able to launch it with Chadash.

In practice, this kind of architecture also allows you to step incrementally into building immutable deployments. Perhaps you are not ready to fully bake AMI's with every deployment, but you have a set of scripts (shell, chef, opsworks, puppet, ansible, etc) that can configure an AMI that you launch to install your app onto it once the instance is running. Great, you can still use Chadash! Simply use CloudInit scripts in your Launch Configuration inside of your cloud formation stack and you have cloud init configure your instance to have it become what you need. This practice is not always ideal, as this can drastically slow down your ability to do a deployment as instances take longer to become healthy and it creates more room for failure - do you really want to depend on these scripts to run successfully when your ASG is trying to add instances due to a scale up event?


#### Walking through a Deployment with Chadash
The following attempts to summarize the steps Chadash does during a typical deployment. This list is not exhaustive; please have a look at the source code for detailed information.

1. Chadash receives a request to deploy a version of a stack. Chadash first validates the user and password sent to the API to deploy the stack - this user and pass is just basic auth and managed in a config file for Chadash. Chadash checks that the user has access to deploy that specific stack which is in the same config file.

2. Once the user is validated, Chadash pulls the cloud formation stack from the configured S3 bucket. Typically, you would manage your stacks configuration with a VCS such as git where changes to your stack configurations get reviewed, and once committed to master, the stack configuration is uploaded to the S3 bucket by automation.

3. With the stack file now in hand and the version number from the API request, using the semantic naming format Chadash makes an API call to CloudFormation looking for all stacks that were deployed from the same stack path. If it finds one, Chadash knows this is deployment who's state must be tracked. If Chadash does not find one, it knows this is the first version of this app and it can simply launch the stack and ignore it.

4. Assuming we are launching a new version of an already deployed stack, Chadash will first lookup the ASG from the previous version using the `ChadashASG` stack output variable and tell that ASG to freeze scaling activities that result in a change in the number of desired instances. Next, Chadash launches your new stack and monitors the status of the stack in CloudFormation for the `CREATE_COMPLETE` state.

5. Once your new stack has reached `CREATE_COMPLETE`, Chadash grabs the new ASG name from the stack output looking for the stack output variable `ChadashASG`. Chadash makes an API call to set the new ASG desired size to the same number as the previously running version and freeze it from scaling further - that way we can continue to handle the same amount of load that was hitting the previous version before the deploy (assuming that the new version of your app doesn't need more instances for the same amount of load). Chadash will then monitor the ASG until the desired number of instances are launched.

6. Once the new ASG has launched the desired number of instances, Chadash will monitor the ELB that the ASG is bound to watching for all of the instance id's that were created as part of this ASG to become healthy and marked as `In Service` on the load balancer. This step has a default timeout of 30 minutes, this is configurable when you make the API call to make a deployment.

7. Assuming that the desired number of instances set on the new ASG get marked as `In Service` on the ELB before 30 minutes, Chadash will then tell the old stack version to delete - allowing Cloud Formation to manage the entire process of deleting every resource that was created. Once Cloud Formation reaches `DELETE_COMPLETE` status on the old stack, Chadash will tell the new ASG to resume scaling activities and the deployment is now complete.

#### Handling Failure
You will most certainly have cases where your next version of your stack fails to deploy. It might be a misconfiguration in your cloud formation template (cloud Formation failing to reach `CREATE_COMPLETE`), or it could be something wrong with your cloud-init scripts, or it could be something wrong with your app or other ELB configuration itself, etc which would prevent instances from ever reaching `In Service` in the ELB.

Regardless of the reason, in the event of any unrecoverable failure during the process Chadash will tell the new stack to simply delete itself and then unfreeze the old stack.

The key here is: during a deployment, the old stack is always healthy and the only thing it can't do is scale up or scale down during the migration window as its ASG was frozen. Otherwise, it's the same as it always has been before you even started the deployment. If anything fails with deploying the new version, the stack for the new thing is deleted and the old stack is unfrozen and allowed to continue scaling as though nothing ever happened.

#### The API
All interactions with Chadash are done via its API. Chadash has no UI of its own. 

To deploy a new stack:
```
POST: /api/deploy/stackpath?timeoutmin=30  //timeoutmin is an optional parameter and by default is set to 30.
Content-Type: application/json
{
  "ami_id": "ami-a4d7b3cc",
  "version": "6.1"
}
```

To delete a stack: (not typically used, but can be used in a build pipeline, for example, to destroy a stack after your load tests are done.
```
POST: /api/delete/stackpath
Content-Type: application/json
{
  "version": "6.1"
}
```

To follow the log of a running stack deploy. Open a web socket connection to:

```GET: /api/ws-log/stackpath```

OR you can follow the log of a running stack deploy with a server-sent-event connection:

```GET: /api/sse-log/stackpath```

**Important Note**: In all of the API examples, the stackpath can be a full path name. For example, assume my S3 bucket that holds my stacks in the `chadash-stacks` folder has more nested folders with paths such as: `/chadash-stacks/teama/productb/prod.json` then the stackpath for such an API call would be `/api/deploy/teama/productb/prod`. The file extension is dropped in these calls.

#### Version Numbers
Version numbers can be very important for cloud-init scripts, etc that might be depending on that version number so the script knows which artifact of your application to fetch to install. The downside of this is - what if you made changes somewhere else in your pipeline - like your load tests, and you want to re-run just the load tests against the same version of the application you already deployed? 

To do this a deploy of a stack version itself needed a version number while still maintaining backwards compatibility. As such, if a version number contains `::` the version string is split on those chars and the left hand side shall be the version of the application and the right hand side shall be the version of the stack deploy. You can see this in practice in the log file example below.

#### Weaknesses
Chadash has one big one: It runs as a single server exposing an API to request deployments and monitor those deployments. The Chadash server itself is not resilient to failure - it is a single point of failure and this is a known disadvantage of Chadash as it can't be clustered right now. Furthermore, since it uses no persistence, in the event Chadash crashes during a deployment, you could end up in a state where both versions of your app are deployed at the same time behind the same ELB and the old version does not shutdown. You should not ever end up in a state however, where you have an outage due to this scenario. Ideally, Chadash would use Persistent FSM's - an experimental feature of Akka 2.4, and a journal such as DynamoDB which would keep the setup overhead of using Chadash low, while providing a means to allow still using a single server, but one that can itself be running inside of an ASG and ELB with just one instance - if the instance crashes, it would be replaced and its state recovered. PR's welcome!




#### Example output of a Chadash log:
The contents of such a log file would have been started from an API call such as this:
```
POST: /api/deploy/teama/projectb-v1/dev
Content-Type: application/json
{
  "ami_id": "ami-a4d7b3cc",
  "version": "GO-9::13"
}
```

```
22:18:03.908 WorkflowStarted
22:18:06.691 Stack JSON data loaded. Querying for existing stack
22:18:06.695 One running stack found, querying for the ASG name
22:18:06.696 ASG found, Requesting to suspend scaling activities for ASG: chadash-teama-projectb-v1-dev-sv13-avGO-9-AppInstanceASGroup-HVIG42QVK2AD
22:18:06.697 ASG: chadash-teama-projectb-v1-dev-sv13-avGO-9-AppInstanceASGroup-HVIG42QVK2AD alarm and scheduled based autoscaling has been frozen for deployment
22:18:06.698 New stack has been created. Stack Name: chadash-teama-projectb-v1-dev-sv14-avGO-9
22:18:06.698 Waiting for new stack to finish launching
22:18:09.948 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:15.000 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:20.064 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:25.125 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:30.190 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:35.249 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:40.305 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:45.367 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:50.447 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:18:55.526 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:00.587 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:05.638 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:10.697 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:15.745 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:20.807 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:25.853 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:30.924 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:35.995 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:41.058 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:46.123 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:51.193 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:19:56.389 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:20:01.332 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:20:06.407 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:20:11.480 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:20:16.543 chadash-teama-projectb-v1-dev-sv14-avGO-9 has not yet reached CREATE_COMPLETE status
22:20:21.633 New stack has reached CREATE_COMPLETE status. chadash-teama-projectb-v1-dev-sv14-avGO-9
22:20:21.681 ASG for new stack found chadash-teama-projectb-v1-dev-sv14-avGO-9-AppInstanceASGroup-MSWCSMIC0NT3, Freezing alarm and scheduled based autoscaling on the new ASG
22:20:21.744 New ASG Frozen, Querying new stack desired size
22:20:21.828 New stack desired size: 1, querying for old stack desired size.
22:20:21.912 Old ASG desired instances: 1
22:20:21.913 New ASG desired size is either greater, or equal to the previous size. Continuing.
22:20:21.918 ASG Desired size has been set, querying ASG for ELB list and attached instance IDs
22:20:22.044 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:20:32.215 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:20:42.363 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:20:52.644 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:21:02.839 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:21:13.043 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:21:23.344 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:21:33.527 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:21:43.700 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:21:53.893 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:22:04.111 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:22:14.291 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:22:24.534 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:22:34.725 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:22:44.881 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:22:55.122 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:23:05.276 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:23:15.429 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:23:25.572 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:23:35.713 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:23:45.872 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:23:56.068 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:24:06.263 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:24:16.465 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:24:26.727 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:24:36.908 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:24:47.097 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:24:57.295 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:25:07.514 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:25:17.714 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:25:28.005 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:25:38.211 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:25:48.411 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:25:58.594 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:26:08.789 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:26:18.987 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:26:29.199 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:26:39.384 1 unhealthy instances still exist on ELB: teama-projectb-dev-elb
22:26:49.548 All instances are reporting healthy on ELB: teama-projectb-dev-elb
22:26:49.549 New ASG up and reporting healthy in the ELB(s)
22:26:49.549 The next version of the stack has been successfully deployed.
22:26:49.755 Deleting old stack: chadash-teama-projectb-v1-dev-sv13-avGO-9
22:26:49.975 Old stack has been requested to be deleted. Monitoring delete progress
22:26:55.201 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:00.276 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:05.343 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:10.425 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:15.485 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:20.548 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:25.609 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:30.666 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:35.762 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:40.859 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:45.938 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:50.989 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:27:56.052 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:01.119 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:06.178 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:11.230 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:16.301 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:21.378 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:26.433 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:31.493 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:36.569 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:41.642 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:46.716 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:51.786 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:28:56.851 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:01.920 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:06.980 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:12.054 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:17.131 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:22.292 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:27.368 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:32.436 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:37.497 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:42.558 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:47.609 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:52.663 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:29:57.717 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:02.787 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:07.843 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:12.901 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:17.956 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:23.038 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:28.086 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:33.159 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:38.246 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:43.302 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:48.368 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:53.494 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:30:58.508 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:31:03.569 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:31:08.619 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:31:13.677 chadash-teama-projectb-v1-dev-sv13-avGO-9 has not yet reached DELETE_COMPLETE status
22:31:18.792 Old stack has reached DELETE_COMPLETE status. Resuming all scaling activities on new stack
22:31:18.849 New ASG scaling activities have been resumed: chadash-teama-projectb-v1-dev-sv14-avGO-9-AppInstanceASGroup-MSWCSMIC0NT3
22:31:18.851 The old stack has been deleted and the new stack's ASG has been unfrozen.
22:31:18.851 WorkflowCompleted
```


#### Deployment & Configuration of Chadash
Chadash deploys as a play application. Run it as you would any other play app. See more info here: 
https://www.playframework.com/documentation/2.3.x/Production

##### AWS IAM Policy
Chadash needs to have access to AWS API's to manage cloud formation stacks, manipulate ASGs, and query ELBS. The minimum required permissions for Chadash to run are. Note that you can likely lock these down further as the symantic naming of everything Chadash creates and modifies will start with the name `chadash-`

```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "chadashCF1",
            "Effect": "Allow",
            "Action": [
                "cloudformation:CreateStack",
                "cloudformation:DescribeStacks",
                "cloudformation:ListStacks"
            ],
            "Resource": "*"
        },
        {
            "Sid": "chadashCF2",
            "Effect": "Allow",
            "Action": [
                "cloudformation:DeleteStack"
            ],
            "Resource": "arn:aws:cloudformation:us-east-1:{ACCT_NUMBER}:stack/chadash-*"
        },
        {
            "Sid": "chadashASG1",
            "Effect": "Allow",
            "Action": [
                "autoscaling:CreateAutoScalingGroup",
                "autoscaling:CreateLaunchConfiguration",
                "autoscaling:DescribeLaunchConfigurations",
                "autoscaling:DescribeScalingActivities",
                "autoscaling:DescribeAutoScalingGroups",
                "autoscaling:DescribeAutoScalingInstances",
                "autoscaling:DeleteAutoScalingGroup",
                "autoscaling:DeleteLaunchConfiguration",
                "autoscaling:DeletePolicy",
                "autoscaling:PutScalingPolicy",
                "autoscaling:ResumeProcesses",
                "autoscaling:SetDesiredCapacity",
                "autoscaling:SuspendProcesses",
                "autoscaling:UpdateAutoScalingGroup"
            ],
            "Resource": "*"
        },
        {
            "Sid": "chadashCW1",
            "Effect": "Allow",
            "Action": [
                "cloudwatch:DeleteAlarms",
                "cloudwatch:PutMetricAlarm"
            ],
            "Resource": "*"
        },
        {
            "Sid": "chadashELB1",
            "Effect": "Allow",
            "Action": [
                "elasticloadbalancing:DescribeInstanceHealth",
                "elasticloadbalancing:DescribeLoadBalancers"
            ],
            "Resource": "*"
        },
        {
            "Sid": "chadashS31",
            "Effect": "Allow",
            "Action": [
                "s3:GetObject"
            ],
            "Resource": ["arn:aws:s3:::{YOUR_S3_BUCKET_HOLDING_YOUR_STACKS}/*"]
        }
    ]
}
```

That policy will be enough to get you started, however, Chadash can only launch stacks that do things with AWS resources where the Chadash user itself has permission to launch those resources. For instance, if your cloud formation stack attempts to work with Lambda functions, create databases, create S3 buckets, etc - then the Chadash user will need access to be able to do those things.

##### App Configuration
There are many ways to pass the AWS credentials into Chadash - the recommended way however, is to use an IAM role. You should configure the application to use the method you wish to use, by default, it will attempt to load credentials using the `DefaultAWSCredentialsProviderChain` provided by the Java AWS SDK if you do not provide a configuration value. The following options are available:
* DefaultAWSCredentialsProviderChain
* TypesafeConfigAWSCredentialsProvider
* InstanceProfileCredentialsProvider
* ClasspathPropertiesFileCredentialsProvider
* EnvironmentVariableCredentialsProvider
* SystemPropertiesCredentialsProvider
The `TypesafeConfigAWSCredentialsProvider` is a custom type that will load the AWS credentials directly from your application config file. If you choose to use this custom type, your config file would like this:
```
aws {
  credentialsProvider = "TypesafeConfigAWSCredentialsProvider"
  accessKey = "access_key"
  secretKey = "secret_key"
}
```

The name of your bucket that contains the `chadash-stacks` folder must also be set in the configuration under the name `chadash.stack-bucket`

The following is a sample of what an application config file could look like:
```
  include "application.conf"
  
  aws {
    credentialsProvider = "InstanceProfileCredentialsProvider"
  }
  
  chadash {
    stack-bucket = "com-mycompany-cloudformationtemplates"
  }
```

##### API Authentication
All of the API endpoints require basic auth. In a large organization, Chadash might be managing hundreds of stacks. Chadash employs a very basic security system around those stacks for who can deploy and destroy what stacks. This is done with simple basic auth and the users and passwords along with what stacks they have access to are stored in a separate config file that can be modified and Chadash will check this file every 2 minutes for changes - allowing you to edit the user list without restarting the Play application.

Set the location of this seperate config file in your apps main config file, like so:

```
authconfig-path = "/opt/conf/path.to.my.chadash.user.file.conf"
```

The contents of the file would look something like this:

```
teama {
  password = "password1"
  stacks = ["teama/*"]
}
productB {
  password = "password2"
  stacks = ["teamc/productB/*"]
}
opsSupport {
  password = "passord3"
  stacks = ["*"]
}
teamb {
  password = "password4"
  stacks = ["teamb/product1/*", "teamb/product2/*"]
}
```

In this manner, you can create users that have access to exactly what they should have access, and no more.


### Using Chadash in practice
Chadash is a nice tool by itself, but really, you want Chadash to be called by your build pipeline, and have that pipeline show a log of the deploy in progress. If the deploy fails, you want your build pipeline to fail so that you can notify the team via the standard mechanisms available by your build tool.

To do this we've authored an overly simple app that makes the API call for your to do your deployment and then makes another API call to follow the logs using the server-sent-event connection. If the deploy ends in an error, the app returns a non-zero exit code resulting in your build tool failing the build (the deploy). The use of the Chadash client is completely optional, see its repository for more information: https://github.com/lifeway/Chadash-Client



### License

This software is licensed under the Apache 2 license, quoted below.

Copyright (C) 2014-2018 LifeWay Christian Resources. (https://www.lifeway.com).

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
