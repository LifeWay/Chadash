package functional

import java.io.ByteArrayInputStream
import java.util.UUID

import actors.DeploymentSupervisor
import actors.WorkflowLog.{LogMessage, WatchThisWorkflow}
import actors.workflow.WorkflowManager
import actors.workflow.WorkflowManager.{StartDeployWorkflow, WorkflowCompleted, WorkflowStarted}
import actors.workflow.tasks.StackCreateCompleteMonitor.Tick
import actors.workflow.tasks._
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{Tag, _}
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

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("newstack/somename", "chadash-newstack-somename-v1-0", "1.0", "test-ami")))
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
    val tmpLoggingActor = system.actorOf(Props(new Actor with ActorLogging {
      override def receive: Receive = {
        case msg: Any => log.debug(msg.toString)
      }
    }))
    val workflowProps = Props(new WorkflowManager(tmpLoggingActor, UpdateStackActorFactory))
    val workflowProxy = WorkflowProxy(sendingProbe, system, workflowProps)

    sendingProbe.send(workflowProxy, StartDeployWorkflow(DeploymentSupervisor.Deploy("updatestack/somename", "chadash-updatestack-somename-v1-1", "1.1", "test-ami")))
    sendingProbe.expectMsg(WorkflowStarted)

    //sendingProbe.expectMsg(WorkflowCompleted)
    fail()
  }

  //  it should "complete a delete workflow when the transition is valid" in {
  //    fail()
  //  }
  //
  //  it should "restart flawlessly if AWS has connection issues" in {
  //    fail()
  //  }
  //
  //  it should "report a deploy failed if AWS returns an exception" in {
  //    fail()
  //  }
}


object WorkflowManagerSystemTest {
  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackLoader => context.actorOf(PropsAndMocks.StackLoader.props, "test-stack-loader")
        case actors.workflow.tasks.StackCreator => context.actorOf(PropsAndMocks.StackCreator.props, "test-stack-creator")
        case actors.workflow.tasks.StackList => context.actorOf(PropsAndMocks.StackList.props, "test-stack-list")
        case actors.workflow.tasks.StackInfo => context.actorOf(PropsAndMocks.StackInfo.props, "test-stack-info")
        case actors.workflow.tasks.FreezeASG => context.actorOf(PropsAndMocks.FreezeASG.props, "test-freeze-asg")
        case actors.workflow.tasks.ASGSize => context.actorOf(PropsAndMocks.ASGSize.props, "test-asg-size" + UUID.randomUUID().toString)
        case actors.workflow.tasks.ASGInfo => context.actorOf(PropsAndMocks.ASGInfo.props, "test-asg-info")
        case _ => ActorFactory(ref, context, name, args: _*)
      }
    }
  }
  object NewStackActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackCreateCompleteMonitor => context.actorOf(PropsAndMocks.StackCreateCompleteMonitor.props, "test-stack-create-complete-monitor")
        case _ => TestActorFactory(ref, context, name, args: _*)
      }
    }
  }
  object UpdateStackActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case actors.workflow.tasks.StackCreateCompleteMonitor => context.actorOf(PropsAndMocks.StackCreateCompleteMonitor.updateProps, "test-stack-create-complete-monitor")
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
      val asgSuccessReq    = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-v1-0")
      val asgOutput        = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-somename-v1-0-asg23562342")
      val asgStack         = new Stack().withOutputs(asgOutput)
      val asgSuccessResult = new DescribeStacksResult().withStacks(asgStack)

      val asgUpdateReq    = new DescribeStacksRequest().withStackName("chadash-updatestack-somename-v1-1")
      val asgUpdateOutput = new Output().withOutputKey("ChadashASG").withOutputValue("chadash-updatestack-somename-v1-1-asg07907234")
      val asgUpdateStack  = new Stack().withOutputs(asgUpdateOutput)
      val asgUpdateResult = new DescribeStacksResult().withStacks(asgUpdateStack)

      Mockito.doReturn(asgSuccessResult).when(mockedClient).describeStacks(asgSuccessReq)
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
        new Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-1"),
        new Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id-2")
      )
      val asg                = new AutoScalingGroup().withLoadBalancerNames("test-elb-name").withInstances(instances: _*)
      val describeASGResult  = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)

      Mockito.when(mockedClient.describeAutoScalingGroups(describeASGRequest)).thenReturn(describeASGResult)

      val props = Props(new ASGInfo(null) {
        override def pauseTime(): FiniteDuration = 5.milliseconds

        override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
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
