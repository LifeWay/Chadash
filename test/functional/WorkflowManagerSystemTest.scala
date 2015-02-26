package functional

import java.io.ByteArrayInputStream

import actors.DeploymentSupervisor
import actors.WorkflowLog.{LogMessage, WatchThisWorkflow}
import actors.workflow.WorkflowManager
import actors.workflow.WorkflowManager.{StartDeleteWorkflow, StartDeployWorkflow, WorkflowCompleted, WorkflowStarted}
import actors.workflow.tasks.StackCreateCompleteMonitor.Tick
import actors.workflow.tasks._
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{Tag, _}
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, DescribeInstanceHealthResult, Instance, InstanceState}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.S3Object
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import play.api.libs.json.{JsString, Json}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WorkflowManagerSystemTest extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                                        with Matchers {

  import functional.WorkflowManagerSystemTest._

  "A WorkflowManager Supervisor" should "complete a new stack workflow deploy" in {
    val sendingProbe = TestProbe()
    val loggingProbe = TestProbe()
    val workflowProps = Props(new WorkflowManager(loggingProbe.ref, NewStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("newstack/somename", "chadash-newstack-somename-v1-0", "1.0", "test-ami", 30)))
    sendingProbe.expectMsg(WorkflowStarted)
    loggingProbe.expectMsg(WatchThisWorkflow)
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Stack JSON data loaded")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("No existing stacks found")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New stack has been created")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Waiting for new stack to finish launching")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("chadash-newstack-somename-v1-0 has not yet reached CREATE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("chadash-newstack-somename-v1-0 has not yet reached CREATE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("chadash-newstack-somename-v1-0 has not yet reached CREATE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New stack has reached CREATE_COMPLETE status. chadash-newstack-somename-v1-0")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("The first version of this stack has been successfully deployed.")
    sendingProbe.expectMsg(WorkflowCompleted)
  }

  it should "complete a upgrade stack workflow when the transition is valid" in {
    val sendingProbe = TestProbe()
    val loggingProbe = TestProbe()

    val workflowProps = Props(new WorkflowManager(loggingProbe.ref, UpdateStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("updatestack/somename", "chadash-updatestack-somename-v1-1", "1.1", "test-ami", 30)))
    sendingProbe.expectMsg(WorkflowStarted)
    loggingProbe.expectMsg(WatchThisWorkflow)
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Stack JSON data loaded")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("One running stack found")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("ASG found, Requesting to suspend scaling activities")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("alarm and scheduled based autoscaling has been frozen for deployment")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New stack has been created")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Waiting for new stack to finish launching")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached CREATE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached CREATE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached CREATE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New stack has reached CREATE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Freezing alarm and scheduled based autoscaling on the new ASG")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New ASG Frozen, Querying old stack size")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Old ASG desired instances: 2, setting new ASG to 2 desired instances")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("ASG Desired size has been set, querying ASG for ELB list and attached instance IDs")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("1 unhealthy instances still exist on ELB: updatestack-somename-elb")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("All instances are reporting healthy on ELB: updatestack-somename-elb")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New ASG up and reporting healthy in the ELB(s)")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("The next version of the stack has been successfully deployed.")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Deleting old stack:")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Old stack has been requested to be deleted. Monitoring delete progress")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Old stack has reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New ASG scaling activities have been resumed:")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("The old stack has been deleted and the new stack's ASG has been unfrozen.")
    sendingProbe.expectMsg(WorkflowCompleted)
  }

  it should "complete a delete workflow when the transition is valid" in {
    val sendingProbe = TestProbe()
    val loggingProbe = TestProbe()

    val workflowProps = Props(new WorkflowManager(loggingProbe.ref, DeleteStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeleteWorkflow("chadash-updatestack-somename-v1-2"))
    sendingProbe.expectMsg(WorkflowStarted)
    loggingProbe.expectMsg(WatchThisWorkflow)
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Deleting stack: chadash")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Stack has been requested to be deleted")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("has not yet reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Stack has reached DELETE_COMPLETE status")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("The stack has been deleted")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Delete complete")
    sendingProbe.expectMsg(WorkflowCompleted)
  }
}


object WorkflowManagerSystemTest {
  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackLoader => context.actorOf(PropsAndMocks.StackLoader.props, name)
        case actors.workflow.tasks.StackCreator => context.actorOf(PropsAndMocks.StackCreator.props, name)
        case actors.workflow.tasks.StackList => context.actorOf(PropsAndMocks.StackList.props, name)
        case actors.workflow.tasks.StackInfo => context.actorOf(PropsAndMocks.StackInfo.props, name)
        case actors.workflow.tasks.FreezeASG => context.actorOf(PropsAndMocks.FreezeASG.props, name)
        case actors.workflow.tasks.ASGSize => context.actorOf(PropsAndMocks.ASGSize.props, name)
        case actors.workflow.tasks.ASGInfo => context.actorOf(PropsAndMocks.ASGInfo.props, name)
        case actors.workflow.tasks.ELBHealthyInstanceChecker => context.actorOf(PropsAndMocks.ELBHealthyInstanceChecker.props, name)
        case actors.workflow.tasks.UnfreezeASG => context.actorOf(PropsAndMocks.UnfreezeASG.props, name)
        case actors.workflow.tasks.DeleteStack => context.actorOf(PropsAndMocks.DeleteStack.props, name)
        case actors.workflow.steps.HealthyInstanceSupervisor => ActorFactory(ref, context, name, args: _*)
        case actors.workflow.steps.DeleteStackSupervisor => ActorFactory(ref, context, name, args: _*)
        case actors.workflow.steps.LoadStackSupervisor => ActorFactory(ref, context, name, args: _*)
        case actors.workflow.steps.TearDownSupervisor => ActorFactory(ref, context, name, args: _*)
        case actors.workflow.steps.ValidateAndFreezeSupervisor => ActorFactory(ref, context, name, args: _*)
        case actors.workflow.steps.NewStackSupervisor => ActorFactory(ref, context, name, args: _*)
      }
    }
  }
  object NewStackActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackCreateCompleteMonitor => context.actorOf(PropsAndMocks.StackCreateCompleteMonitor.props, name)
        case _ => TestActorFactory(ref, context, name, args: _*)
      }
    }
  }
  object UpdateStackActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackCreateCompleteMonitor => context.actorOf(PropsAndMocks.StackCreateCompleteMonitor.updateProps, name)
        case actors.workflow.tasks.StackDeleteCompleteMonitor => context.actorOf(PropsAndMocks.StackDeleteCompleteMonitor.props, name)
        case _ => TestActorFactory(ref, context, name, args: _*)
      }
    }
  }
  object DeleteStackActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackDeleteCompleteMonitor => context.actorOf(PropsAndMocks.StackDeleteCompleteMonitor.deleteStack, name)
        case _ => TestActorFactory(ref, context, name, args: _*)
      }
    }
  }

  object PropsAndMocks extends MockitoSugar {

    object StackLoader {
      val mockedClient    = mock[AmazonS3]
      val s3successObject = new S3Object()
      s3successObject.setBucketName("test-bucket-name")
      s3successObject.setKey("chadash-stacks/newstack/somename.json")
      s3successObject.setObjectContent(new ByteArrayInputStream(Json.obj("test" -> JsString("success")).toString().getBytes("UTF-8")))

      val updateObject = new S3Object()
      updateObject.setBucketName("test-bucket-name")
      updateObject.setKey("chadash-stacks/updatestack/somename.json")
      updateObject.setObjectContent(new ByteArrayInputStream(Json.obj("test" -> JsString("success")).toString().getBytes("UTF-8")))

      Mockito.doReturn(s3successObject).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/newstack/somename.json")
      Mockito.doReturn(updateObject).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/updatestack/somename.json")

      val props = Props(new StackLoader(null, "test-bucket-name") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def s3Client(credentials: AWSCredentials): AmazonS3 = mockedClient
      })
    }

    object StackList {

      import actors.workflow.tasks.StackListSpec._

      val mockedClient          = mock[AmazonCloudFormation]
      val req                   = new ListStacksRequest().withStackStatusFilters(stackStatusFilters: _*)
      val successStackSummaries = Seq(new StackSummary().withStackName("chadash-updatestack-somename-v1-0"), new StackSummary().withStackName("other-stack-name"))
      val successResp           = new ListStacksResult().withStackSummaries(successStackSummaries: _*)

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).listStacks(org.mockito.Matchers.anyObject())
      Mockito.doReturn(successResp).doReturn(successResp).when(mockedClient).listStacks(req)

      val props = Props(new StackList(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
      })
    }

    object StackCreator {
      val mockedClient  = mock[AmazonCloudFormation]
      val appVersionTag = new Tag().withKey("ApplicationVersion").withValue("1.0")
      val params        = Seq(
        new Parameter().withParameterKey("ImageId").withParameterValue("test-ami"),
        new Parameter().withParameterKey("ApplicationVersion").withParameterValue("1.0")
      )
      val successReq    = new CreateStackRequest().withTemplateBody(Json.obj("test" -> "success").toString()).withTags(appVersionTag).withParameters(params: _*).withStackName("chadash-newstack-somename-v1-0")


      val updateAppVersionTag = new Tag().withKey("ApplicationVersion").withValue("1.1")
      val updateParams        = Seq(
        new Parameter().withParameterKey("ImageId").withParameterValue("test-ami"),
        new Parameter().withParameterKey("ApplicationVersion").withParameterValue("1.1")
      )
      val updateReq           = new CreateStackRequest().withTemplateBody(Json.obj("test" -> "success").toString()).withTags(updateAppVersionTag).withParameters(updateParams: _*).withStackName("chadash-updatestack-somename-v1-1")

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).createStack(org.mockito.Matchers.anyObject())
      Mockito.doReturn(null).when(mockedClient).createStack(successReq)
      Mockito.doReturn(null).when(mockedClient).createStack(updateReq)

      val props = Props(new StackCreator(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
      })
    }

    object StackCreateCompleteMonitor {
      val mockedClient            = mock[AmazonCloudFormation]
      val createCompleteReq       = new DescribeStacksRequest().withStackName("chadash-newstack-somename-v1-0")
      val createCompleteUpdateReq = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-v1-1")
      val stackPending            = new Stack().withStackStatus(StackStatus.CREATE_IN_PROGRESS)
      val stackComplete           = new Stack().withStackStatus(StackStatus.CREATE_COMPLETE)
      val stackPendingResp        = new DescribeStacksResult().withStacks(stackPending)
      val stackCompleteResp       = new DescribeStacksResult().withStacks(stackComplete)

      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(createCompleteReq)
      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(createCompleteUpdateReq)

      val props = Props(new StackCreateCompleteMonitor(null, "chadash-newstack-somename-v1-0") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

        override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, Tick)
      })

      val updateProps = Props(new StackCreateCompleteMonitor(null, "chadash-updatestack-somename-v1-1") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

        override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, Tick)
      })

    }

    object StackInfo {
      val mockedClient     = mock[AmazonCloudFormation]
      val successReq       = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-v1-0")
      val asgOutput        = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-somename-v1-0-asg23562342")
      val asgStack         = new Stack().withOutputs(asgOutput)
      val asgSuccessResult = new DescribeStacksResult().withStacks(asgStack)

      val asgUpdateReq    = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-v1-1")
      val asgUpdateOutput = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-somename-v1-1-asg07907234")
      val asgUpdateStack  = new Stack().withOutputs(asgUpdateOutput)
      val asgUpdateResult = new DescribeStacksResult().withStacks(asgUpdateStack)

      val idStack         = new Stack().withStackId("some-stack-id")
      val idSuccessResult = new DescribeStacksResult().withStacks(idStack)

      val deleteReq           = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-v1-2")
      val deleteReqOutput     = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-somename-v1-2-asg35978123")
      val deleteStackId       = new Stack().withStackId("delete-stack-id")
      val deleteStackIdResult = new DescribeStacksResult().withStacks(deleteStackId)


      Mockito.doReturn(asgSuccessResult).doReturn(idSuccessResult).when(mockedClient).describeStacks(successReq)
      Mockito.doReturn(deleteStackIdResult).when(mockedClient).describeStacks(deleteReq)
      Mockito.doReturn(asgUpdateResult).when(mockedClient).describeStacks(asgUpdateReq)
      Mockito.doReturn(asgUpdateResult).when(mockedClient).describeStacks(asgUpdateReq)


      val props = Props(new StackInfo(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
      })
    }

    object FreezeASG {
      val mockedClient     = mock[AmazonAutoScaling]
      val successReq       = new SuspendProcessesRequest().withAutoScalingGroupName("chadash-updatestack-somename-v1-0-asg23562342").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)
      val updateSuccessReq = new SuspendProcessesRequest().withAutoScalingGroupName("chadash-updatestack-somename-v1-1-asg07907234").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)


      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).suspendProcesses(org.mockito.Matchers.anyObject())
      Mockito.doNothing().when(mockedClient).suspendProcesses(successReq)
      Mockito.doNothing().when(mockedClient).suspendProcesses(updateSuccessReq)

      val props = Props(new FreezeASG(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
      })
    }

    object ASGSize {
      val mockedClient         = mock[AmazonAutoScaling]
      val describeASGReq       = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-somename-v1-0-asg23562342")
      val asg                  = new AutoScalingGroup().withDesiredCapacity(2)
      val describeASGResult    = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)
      val desiredCapSetRequest = new SetDesiredCapacityRequest().withAutoScalingGroupName("chadash-updatestack-somename-v1-1-asg07907234").withDesiredCapacity(2)

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).setDesiredCapacity(org.mockito.Matchers.anyObject())
      Mockito.doReturn(describeASGResult).when(mockedClient).describeAutoScalingGroups(describeASGReq)
      Mockito.doNothing().when(mockedClient).setDesiredCapacity(desiredCapSetRequest)

      val props = Props(new ASGSize(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
      })
    }

    object ASGInfo {
      val mockedClient       = mock[AmazonAutoScaling]
      val describeASGRequest = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-somename-v1-1-asg07907234")
      val instances          = Seq(
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-1"),
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-2")
      )
      val asg                = new AutoScalingGroup().withLoadBalancerNames("updatestack-somename-elb").withInstances(instances: _*)
      val describeASGResult  = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)

      Mockito.when(mockedClient.describeAutoScalingGroups(describeASGRequest)).thenReturn(describeASGResult)

      val props = Props(new ASGInfo(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
      })
    }

    object ELBHealthyInstanceChecker {
      val mockedClient = mock[AmazonElasticLoadBalancing]

      val instances                = Seq(new Instance("test-instance-id-1"), new Instance("test-instance-id-2"))
      val notHealthyInstanceStates = Seq(new InstanceState().withState("InService"), new InstanceState().withState("OutOfService"))
      val healthyStates            = Seq(new InstanceState().withState("InService"), new InstanceState().withState("InService"))
      val successReq               = new DescribeInstanceHealthRequest().withLoadBalancerName("updatestack-somename-elb").withInstances(instances: _*)
      val successResultAllHealthy  = new DescribeInstanceHealthResult().withInstanceStates(healthyStates: _*)
      val successResultNotHealthy  = new DescribeInstanceHealthResult().withInstanceStates(notHealthyInstanceStates: _*)

      Mockito.when(mockedClient.describeInstanceHealth(successReq)).thenReturn(successResultNotHealthy).thenReturn(successResultAllHealthy)

      val props = Props(new ELBHealthyInstanceChecker(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def elasticLoadBalancingClient(credentials: AWSCredentials) = mockedClient
      })
    }

    object StackDeleteCompleteMonitor {
      val mockedClient       = mock[AmazonCloudFormation]
      val deleteCompleteReq  = new DescribeStacksRequest().withStackName("some-stack-id")
      val deleteCompleteReq2 = new DescribeStacksRequest().withStackName("delete-stack-id")
      val stackComplete      = new Stack().withStackStatus(StackStatus.DELETE_COMPLETE)
      val stackPending       = new Stack().withStackStatus(StackStatus.DELETE_IN_PROGRESS)
      val stackPendingResp   = new DescribeStacksResult().withStacks(stackPending)
      val stackCompleteResp  = new DescribeStacksResult().withStacks(stackComplete)

      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(deleteCompleteReq)
      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(deleteCompleteReq2)

      val props = Props(new StackDeleteCompleteMonitor(null, "some-stack-id", "chadash-newstack-somename-v1-0") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

        override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, actors.workflow.tasks.StackDeleteCompleteMonitor.Tick)
      })

      val deleteStack = Props(new StackDeleteCompleteMonitor(null, "delete-stack-id", "chadash-updatestack-somename-v1-2") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

        override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, actors.workflow.tasks.StackDeleteCompleteMonitor.Tick)
      })
    }

    object UnfreezeASG {
      val mockedClient = mock[AmazonAutoScaling]
      val successReq   = new ResumeProcessesRequest().withAutoScalingGroupName("chadash-updatestack-somename-v1-1-asg07907234")

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).resumeProcesses(org.mockito.Matchers.anyObject())
      Mockito.doNothing().when(mockedClient).resumeProcesses(successReq)

      val props = Props(new UnfreezeASG(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
      })
    }

    object DeleteStack {
      val mockedClient   = mock[AmazonCloudFormation]
      val successReq     = new DeleteStackRequest().withStackName("chadash-updatestack-somename-v1-0")
      val deleteStackReq = new DeleteStackRequest().withStackName("chadash-updatestack-somename-v1-2")

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).deleteStack(org.mockito.Matchers.anyObject())
      Mockito.doNothing().when(mockedClient).deleteStack(successReq)
      Mockito.doNothing().when(mockedClient).deleteStack(deleteStackReq)

      val props = Props(new DeleteStack(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
      })
    }
  }


  object WorkflowProxy {
    def apply(probe: TestProbe, actorSystem: ActorSystem, workflowProps: Props): ActorRef = {
      actorSystem.actorOf(Props(new Actor {
        val parent = context.actorOf(Props(new Actor {
          val child = context.actorOf(workflowProps, "workflowTest")

          def receive = {
            case x if sender() == child => context.parent ! x
            case x => child forward x
          }
        }))

        def receive = {
          case x if sender() == parent => probe.ref forward x
          case x => parent forward x
        }
      }))
    }
  }
}
