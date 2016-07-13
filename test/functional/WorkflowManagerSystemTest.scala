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
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import play.api.libs.json.{JsString, Json}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WorkflowManagerSystemTest extends TestKit(ActorSystem("WorkflowManagerSystemTestKit", TestConfiguration.testConfig)) with FlatSpecLike
                                        with Matchers with BeforeAndAfterAll {

  import functional.WorkflowManagerSystemTest._

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A WorkflowManager Supervisor" should "complete a new stack workflow deploy" in {
    val sendingProbe = TestProbe()
    val loggingProbe = TestProbe()
    val workflowProps = Props(new WorkflowManager(loggingProbe.ref, NewStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("newstack/somename", "chadash-newstack-somename-v1-0", DeploymentSupervisor.buildVersion("1.0"), "test-ami", 30)))
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

  it should "complete a upgrade stack workflow when the transition is valid with no new ASG resizing" in {
    val sendingProbe = TestProbe()
    val loggingProbe = TestProbe()

    val workflowProps = Props(new WorkflowManager(loggingProbe.ref, UpdateStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("updatestack/somename", "chadash-updatestack-somename-sv20-av1-1", DeploymentSupervisor.buildVersion("1.1::20"), "test-ami", 30)))
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
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New stack has reached CREATE_COMPLETE status. chadash-updatestack-somename-sv20-av1-1")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Freezing alarm and scheduled based autoscaling on the new ASG")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New ASG Frozen, Querying new stack desired size")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New stack desired size: 2, querying for old stack desired size.")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Old ASG desired instances: 2")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New ASG desired size is either greater, or equal to the previous size. Continuing.")
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

  it should "complete a upgrade stack workflow when the transition is valid with new ASG resize growing to old size" in {
    val sendingProbe = TestProbe()
    val loggingProbe = TestProbe()

    val workflowProps = Props(new WorkflowManager(loggingProbe.ref, GrowStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("updatestack/growstack", "chadash-updatestack-growstack-v1-2", DeploymentSupervisor.buildVersion("1.2"), "test-ami", 30)))
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
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New ASG Frozen, Querying new stack desired size")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("New stack desired size: 2, querying for old stack desired size.")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Old ASG desired instances: 4")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Setting new ASG to 4 desired instances")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("ASG Desired size has been set, querying ASG for ELB list and attached instance IDs")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("1 unhealthy instances still exist on ELB: updatestack-growstack-elb")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("All instances are reporting healthy on ELB: updatestack-growstack-elb")
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

  it should "report failure if the stack + version number already exist" in {
    val sendingProbe = TestProbe()
    val loggingProbe = TestProbe()

    val workflowProps = Props(new WorkflowManager(loggingProbe.ref, ExistingStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("existingstack/somename", "chadash-existingstack-somename-v1-0", DeploymentSupervisor.buildVersion("1.0"), "test-ami", 30)))
    sendingProbe.expectMsg(WorkflowStarted)
    loggingProbe.expectMsg(WatchThisWorkflow)
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Stack JSON data loaded")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Stack version already exists")
    loggingProbe.expectMsgClass(classOf[LogMessage]).message should include("Workflow is being stopped")
    sendingProbe.expectMsgClass(classOf[WorkflowManager.WorkflowFailed])
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
  object GrowStackActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackCreateCompleteMonitor => context.actorOf(PropsAndMocks.StackCreateCompleteMonitor.growProps, name)
        case actors.workflow.tasks.StackDeleteCompleteMonitor => context.actorOf(PropsAndMocks.StackDeleteCompleteMonitor.growProps, name)
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
  object ExistingStackActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackList => context.actorOf(PropsAndMocks.ExistingStack.props, name)
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

      val updateObject2 = new S3Object()
      updateObject2.setBucketName("test-bucket-name")
      updateObject2.setKey("chadash-stacks/updatestack/growstack.json")
      updateObject2.setObjectContent(new ByteArrayInputStream(Json.obj("test" -> JsString("success")).toString().getBytes("UTF-8")))

      val existingObject = new S3Object()
      existingObject.setBucketName("test-bucket-name")
      existingObject.setKey("chadash-stacks/existingstack/somename.json")
      existingObject.setObjectContent(new ByteArrayInputStream(Json.obj("test" -> JsString("success")).toString().getBytes("UTF-8")))

      Mockito.doReturn(s3successObject).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/newstack/somename.json")
      Mockito.doReturn(updateObject).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/updatestack/somename.json")
      Mockito.doReturn(updateObject2).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/updatestack/growstack.json")
      Mockito.doReturn(existingObject).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/existingstack/somename.json")

      val props = Props(new StackLoader(null, "test-bucket-name") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def s3Client(credentials: AWSCredentials): AmazonS3 = mockedClient
      })
    }

    object StackList {

      val mockedClient          = mock[AmazonCloudFormation]
      val req                   = new ListStacksRequest().withStackStatusFilters(actors.workflow.tasks.StackList.stackStatusFilters: _*)
      val successStackSummaries = Seq(new StackSummary().withStackName("chadash-updatestack-somename-v1-0"), new StackSummary().withStackName("chadash-updatestack-growstack-v1-1"),  new StackSummary().withStackName("other-stack-name"))
      val successResp           = new ListStacksResult().withStackSummaries(successStackSummaries: _*)

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).listStacks(org.mockito.Matchers.anyObject())
      Mockito.doReturn(successResp).when(mockedClient).listStacks(req)

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
      val successReq    = new CreateStackRequest().withTemplateBody(Json.obj("test" -> "success").toString()).withTags(appVersionTag).withParameters(params: _*).withCapabilities(Capability.CAPABILITY_IAM).withStackName("chadash-newstack-somename-v1-0")


      val updateAppVersionTags = Seq(
        new Tag().withKey("ApplicationVersion").withValue("1.1"),
        new Tag().withKey("StackVersion").withValue("20")
      )
      val updateParams        = Seq(
        new Parameter().withParameterKey("ImageId").withParameterValue("test-ami"),
        new Parameter().withParameterKey("ApplicationVersion").withParameterValue("1.1")
      )
      val updateReq           = new CreateStackRequest().withTemplateBody(Json.obj("test" -> "success").toString()).withTags(updateAppVersionTags: _*).withParameters(updateParams: _*).withCapabilities(Capability.CAPABILITY_IAM).withStackName("chadash-updatestack-somename-sv20-av1-1")


      val growAppVersionTag = new Tag().withKey("ApplicationVersion").withValue("1.2")
      val growParams        = Seq(
        new Parameter().withParameterKey("ImageId").withParameterValue("test-ami"),
        new Parameter().withParameterKey("ApplicationVersion").withParameterValue("1.2")
      )
      val growReq           = new CreateStackRequest().withTemplateBody(Json.obj("test" -> "success").toString()).withTags(growAppVersionTag).withParameters(growParams: _*).withCapabilities(Capability.CAPABILITY_IAM).withStackName("chadash-updatestack-growstack-v1-2")


      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).createStack(org.mockito.Matchers.anyObject())
      Mockito.doReturn(null).when(mockedClient).createStack(successReq)
      Mockito.doReturn(null).when(mockedClient).createStack(updateReq)
      Mockito.doReturn(null).when(mockedClient).createStack(growReq)

      val props = Props(new StackCreator(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
      })
    }

    object StackCreateCompleteMonitor {
      val mockedClient            = mock[AmazonCloudFormation]
      val createCompleteReq       = new DescribeStacksRequest().withStackName("chadash-newstack-somename-v1-0")
      val createCompleteUpdateReq = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-sv20-av1-1")
      val growCompleteUpdateReq   = new DescribeStacksRequest().withStackName("chadash-updatestack-growstack-v1-2")
      val stackPending            = new Stack().withStackStatus(StackStatus.CREATE_IN_PROGRESS)
      val stackComplete           = new Stack().withStackStatus(StackStatus.CREATE_COMPLETE)
      val stackPendingResp        = new DescribeStacksResult().withStacks(stackPending)
      val stackCompleteResp       = new DescribeStacksResult().withStacks(stackComplete)

      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(createCompleteReq)
      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(createCompleteUpdateReq)
      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(growCompleteUpdateReq)

      val props = Props(new StackCreateCompleteMonitor(null, "chadash-newstack-somename-v1-0") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

        override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, Tick)
      })

      val updateProps = Props(new StackCreateCompleteMonitor(null, "chadash-updatestack-somename-sv20-av1-1") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

        override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, Tick)
      })

      val growProps = Props(new StackCreateCompleteMonitor(null, "chadash-updatestack-growstack-v1-2") {
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

      val asgUpdateReq    = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-sv20-av1-1")
      val asgUpdateOutput = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-somename-sv20-av1-1-asg07907234")
      val asgUpdateStack  = new Stack().withOutputs(asgUpdateOutput)
      val asgUpdateResult = new DescribeStacksResult().withStacks(asgUpdateStack)

      val growUpdateReq    = new DescribeStacksRequest().withStackName("chadash-updatestack-growstack-v1-1")
      val growUpdateOutput = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-growstack-v1-1-asg07907236")
      val growUpdateStack  = new Stack().withOutputs(growUpdateOutput)
      val growUpdateResult = new DescribeStacksResult().withStacks(growUpdateStack)

      val growNewUpdateReq    = new DescribeStacksRequest().withStackName("chadash-updatestack-growstack-v1-2")
      val growNewUpdateOutput = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-growstack-v1-2-asg07907237")
      val growNewUpdateStack  = new Stack().withOutputs(growNewUpdateOutput)
      val growNewUpdateResult = new DescribeStacksResult().withStacks(growNewUpdateStack)


      val idStack         = new Stack().withStackId("some-stack-id")
      val idSuccessResult = new DescribeStacksResult().withStacks(idStack)

      val growIdStack         = new Stack().withStackId("some-growstack-id")
      val growIdSuccessResult = new DescribeStacksResult().withStacks(growIdStack)


      val deleteReq           = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-v1-2")
      val deleteReqOutput     = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-somename-v1-2-asg35978123")
      val deleteStackId       = new Stack().withStackId("delete-stack-id")
      val deleteStackIdResult = new DescribeStacksResult().withStacks(deleteStackId)


      Mockito.doReturn(asgSuccessResult).doReturn(idSuccessResult).doReturn(growIdSuccessResult).when(mockedClient).describeStacks(successReq)
      Mockito.doReturn(deleteStackIdResult).when(mockedClient).describeStacks(deleteReq)
      Mockito.doReturn(asgUpdateResult).when(mockedClient).describeStacks(asgUpdateReq)
      Mockito.doReturn(growUpdateResult).when(mockedClient).describeStacks(growUpdateReq)
      Mockito.doReturn(growNewUpdateResult).when(mockedClient).describeStacks(growNewUpdateReq)


      val props = Props(new StackInfo(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
      })
    }

    object FreezeASG {
      val mockedClient     = mock[AmazonAutoScaling]
      val successReq       = new SuspendProcessesRequest().withAutoScalingGroupName("chadash-updatestack-somename-v1-0-asg23562342").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)
      val updateSuccessReq = new SuspendProcessesRequest().withAutoScalingGroupName("chadash-updatestack-somename-sv20-av1-1-asg07907234").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

      val growReq        = new SuspendProcessesRequest().withAutoScalingGroupName("chadash-updatestack-growstack-v1-1-asg07907236").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)
      val growNewASGReq  = new SuspendProcessesRequest().withAutoScalingGroupName("chadash-updatestack-growstack-v1-2-asg07907237").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).suspendProcesses(org.mockito.Matchers.anyObject())
      Mockito.doNothing().when(mockedClient).suspendProcesses(successReq)
      Mockito.doNothing().when(mockedClient).suspendProcesses(updateSuccessReq)
      Mockito.doNothing().when(mockedClient).suspendProcesses(growReq)
      Mockito.doNothing().when(mockedClient).suspendProcesses(growNewASGReq)

      val props = Props(new FreezeASG(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
      })
    }

    object ASGSize {
      val mockedClient         = mock[AmazonAutoScaling]
      val describeASGReq       = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-somename-v1-0-asg23562342")
      val oldAsg               = new AutoScalingGroup().withDesiredCapacity(2)
      val describeNewASGReq    = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-somename-sv20-av1-1-asg07907234")
      val newAsg               = new AutoScalingGroup().withDesiredCapacity(2)
      val describeASGResult    = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(oldAsg)
      val describeNewASGResult = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(newAsg)

      val growASGReq                   = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-growstack-v1-1-asg07907236")
      val growOldAsg                   = new AutoScalingGroup().withDesiredCapacity(4)
      val growOldAsgLarger             = new AutoScalingGroup().withDesiredCapacity(4)
      val growDescribeNewASGReq        = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-growstack-v1-2-asg07907237")
      val growNewAsg                   = new AutoScalingGroup().withDesiredCapacity(2)
      val growDescribeASGResult        = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(growOldAsg)
      val growDescribeNewASGResult     = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(growNewAsg)
      val growDesiredCapSetRequest     = new SetDesiredCapacityRequest().withAutoScalingGroupName("chadash-updatestack-growstack-v1-2-asg07907237").withDesiredCapacity(4)


      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).setDesiredCapacity(org.mockito.Matchers.anyObject())
      Mockito.doReturn(describeASGResult).when(mockedClient).describeAutoScalingGroups(describeASGReq)
      Mockito.doReturn(describeNewASGResult).when(mockedClient).describeAutoScalingGroups(describeNewASGReq)

      Mockito.doReturn(growDescribeASGResult).when(mockedClient).describeAutoScalingGroups(growASGReq)
      Mockito.doReturn(growDescribeNewASGResult).when(mockedClient).describeAutoScalingGroups(growDescribeNewASGReq)
      Mockito.doNothing().when(mockedClient).setDesiredCapacity(growDesiredCapSetRequest)

      val props = Props(new ASGSize(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
      })
    }

    object ASGInfo {
      val mockedClient       = mock[AmazonAutoScaling]
      val describeASGRequest = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-somename-sv20-av1-1-asg07907234")
      val instances          = Seq(
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-1"),
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-2")
      )
      val asg                = new AutoScalingGroup().withLoadBalancerNames("updatestack-somename-elb").withInstances(instances: _*)
      val describeASGResult  = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)

      val growASGRequest         = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("chadash-updatestack-growstack-v1-2-asg07907237")
      val growInstances          = Seq(
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-1"),
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-2"),
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-3"),
        new com.amazonaws.services.autoscaling.model.Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-4")
      )
      val growAsg                = new AutoScalingGroup().withLoadBalancerNames("updatestack-growstack-elb").withInstances(growInstances: _*)
      val growASGResult  = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(growAsg)


      Mockito.when(mockedClient.describeAutoScalingGroups(describeASGRequest)).thenReturn(describeASGResult)
      Mockito.when(mockedClient.describeAutoScalingGroups(growASGRequest)).thenReturn(growASGResult)

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

      val growInstances                = Seq(new Instance("test-instance-id-1"), new Instance("test-instance-id-2"), new Instance("test-instance-id-3"), new Instance("test-instance-id-4"))
      val growNotHealthyInstanceStates = Seq(new InstanceState().withState("InService"), new InstanceState().withState("OutOfService"), new InstanceState().withState("InService"), new InstanceState().withState("InService"))
      val growHealthyStates            = Seq(new InstanceState().withState("InService"), new InstanceState().withState("InService"), new InstanceState().withState("InService"), new InstanceState().withState("InService"))
      val growSuccessReq               = new DescribeInstanceHealthRequest().withLoadBalancerName("updatestack-growstack-elb").withInstances(growInstances: _*)
      val growSuccessResultAllHealthy  = new DescribeInstanceHealthResult().withInstanceStates(growHealthyStates: _*)
      val growSuccessResultNotHealthy  = new DescribeInstanceHealthResult().withInstanceStates(growNotHealthyInstanceStates: _*)

      Mockito.when(mockedClient.describeInstanceHealth(successReq)).thenReturn(successResultNotHealthy).thenReturn(successResultAllHealthy)
      Mockito.when(mockedClient.describeInstanceHealth(growSuccessReq)).thenReturn(growSuccessResultNotHealthy).thenReturn(growSuccessResultAllHealthy)

      val props = Props(new ELBHealthyInstanceChecker(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def elasticLoadBalancingClient(credentials: AWSCredentials) = mockedClient
      })
    }

    object StackDeleteCompleteMonitor {
      val mockedClient       = mock[AmazonCloudFormation]
      val deleteCompleteReq  = new DescribeStacksRequest().withStackName("some-stack-id")
      val growDeleteComplete = new DescribeStacksRequest().withStackName("some-growstack-id")
      val deleteCompleteReq2 = new DescribeStacksRequest().withStackName("delete-stack-id")
      val stackComplete      = new Stack().withStackStatus(StackStatus.DELETE_COMPLETE)
      val stackPending       = new Stack().withStackStatus(StackStatus.DELETE_IN_PROGRESS)
      val stackPendingResp   = new DescribeStacksResult().withStacks(stackPending)
      val stackCompleteResp  = new DescribeStacksResult().withStacks(stackComplete)

      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(deleteCompleteReq)
      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(growDeleteComplete)
      Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(deleteCompleteReq2)

      val props = Props(new StackDeleteCompleteMonitor(null, "some-stack-id", "chadash-newstack-somename-v1-0") {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

        override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, actors.workflow.tasks.StackDeleteCompleteMonitor.Tick)
      })

      val growProps = Props(new StackDeleteCompleteMonitor(null, "some-growstack-id", "chadash-newstack-growstack-v1-1") {
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
      val successReq   = new ResumeProcessesRequest().withAutoScalingGroupName("chadash-updatestack-somename-sv20-av1-1-asg07907234")
      val growReq      = new ResumeProcessesRequest().withAutoScalingGroupName("chadash-updatestack-growstack-v1-2-asg07907237")

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).resumeProcesses(org.mockito.Matchers.anyObject())
      Mockito.doNothing().when(mockedClient).resumeProcesses(successReq)
      Mockito.doNothing().when(mockedClient).resumeProcesses(growReq)

      val props = Props(new UnfreezeASG(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
      })
    }

    object DeleteStack {
      val mockedClient   = mock[AmazonCloudFormation]
      val successReq     = new DeleteStackRequest().withStackName("chadash-updatestack-somename-v1-0")
      val growReq        = new DeleteStackRequest().withStackName("chadash-updatestack-growstack-v1-1")
      val deleteStackReq = new DeleteStackRequest().withStackName("chadash-updatestack-somename-v1-2")

      Mockito.doThrow(new IllegalArgumentException).when(mockedClient).deleteStack(org.mockito.Matchers.anyObject())
      Mockito.doNothing().when(mockedClient).deleteStack(successReq)
      Mockito.doNothing().when(mockedClient).deleteStack(growReq)
      Mockito.doNothing().when(mockedClient).deleteStack(deleteStackReq)

      val props = Props(new DeleteStack(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
      })
    }

    object ExistingStack {
      val mockedClient = mock[AmazonCloudFormation]
      val existingReq  = new ListStacksRequest().withStackStatusFilters(actors.workflow.tasks.StackList.stackStatusFilters.toArray: _*)
      val stackSummaries = new StackSummary().withStackName("chadash-existingstack-somename-v1-0")
      val response = new ListStacksResult().withStackSummaries(stackSummaries)

      Mockito.doReturn(response).when(mockedClient).listStacks(existingReq)

      val props = Props(new actors.workflow.tasks.StackList(null) {
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
