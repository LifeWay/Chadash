package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.FreezeASG.{FreezeASGCommand, FreezeASGCompleted}
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest
import com.amazonaws.{AmazonClientException, AmazonServiceException}
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
  val successReq   = new SuspendProcessesRequest().withAutoScalingGroupName("freeze-success").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)
  val reqFail      = new SuspendProcessesRequest().withAutoScalingGroupName("fail").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)
  val reqClientExc = new SuspendProcessesRequest().withAutoScalingGroupName("client-exception").withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

  //If we don't check Mock data response, we must have throw an exception if we didn't match the request.
  Mockito.doThrow(new IllegalArgumentException).when(mockedClient).suspendProcesses(org.mockito.Matchers.anyObject())
  Mockito.doNothing().when(mockedClient).suspendProcesses(successReq)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).suspendProcesses(reqFail)
  Mockito.doThrow(new AmazonClientException("connection problems")).doNothing().when(mockedClient).suspendProcesses(reqClientExc)

  "A FreezeASG actor" should " return a freeze completed response if successful" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, FreezeASG, system, TestActorFactory)

    probe.send(proxy, FreezeASGCommand("freeze-success"))
    probe.expectMsg(FreezeASGCompleted("freeze-success"))
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, FreezeASG, system, TestActorFactory)

    probe.send(proxy, FreezeASGCommand("fail"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, FreezeASG, system, TestActorFactory)

    probe.send(proxy, FreezeASGCommand("client-exception"))
    probe.expectMsg(FreezeASGCompleted("client-exception"))
  }

  val props = Props(new FreezeASG(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def autoScalingClient(credentials: AWSCredentialsProvider): AmazonAutoScaling = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case FreezeASG => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }

}
