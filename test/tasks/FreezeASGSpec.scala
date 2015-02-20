package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.AWSSupervisorStrategy
import actors.workflow.tasks.{ASGInfo, FreezeASG}
import actors.workflow.tasks.FreezeASG.{FreezeASGCommand, FreezeASGCompleted}
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class FreezeASGSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                            with Matchers
                            with MockitoSugar {

  val mockedClient = mock[AmazonAutoScaling]
  val successReq   = new SuspendProcessesRequest().withAutoScalingGroupName("test-asg").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)
  val reqFail      = new SuspendProcessesRequest().withAutoScalingGroupName("fail").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)
  val reqClientExc = new SuspendProcessesRequest().withAutoScalingGroupName("client-exception").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

  Mockito.doNothing().when(mockedClient).suspendProcesses(successReq)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).suspendProcesses(reqFail)
  Mockito.doThrow(new AmazonClientException("connection problems")).doNothing().when(mockedClient).suspendProcesses(reqClientExc)


  val props = Props(new FreezeASG(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      //Match on actor classes you care about, pass the rest onto the "prod" factory.
      ref match {
        case FreezeASG => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }

  "A FreezeASG actor" should " return a freeze completed response if successful" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = TestActorFactory(FreezeASG, context, "", null)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, FreezeASGCommand("freeze-success"))
    proxy.expectMsg(FreezeASGCompleted("freeze-success"))
  }

  it should "throw an exception if AWS is down" in {
    val proxy = TestProbe()
    val grandparent = system.actorOf(Props(new Actor {
      val parent = context.actorOf(Props(new Actor with AWSSupervisorStrategy {
        val child = TestActorFactory(FreezeASG, context, "asgInfo", null)
        def receive = {
          case x => child forward x
        }
      }))

      def receive = {
        case x: LogMessage => proxy.ref forward x
        case x => parent forward x
      }
    }))

    proxy.send(grandparent, FreezeASGCommand("fail"))
    val msg = proxy.expectMsgClass(classOf[LogMessage])
    msg.message should include ("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = TestActorFactory(FreezeASG, context, "", null)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, FreezeASGCommand("client-exception"))
    proxy.expectMsg(FreezeASGCompleted("client-exception"))
  }

}
