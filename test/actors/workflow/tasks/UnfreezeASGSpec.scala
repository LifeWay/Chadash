package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.UnfreezeASG.{UnfreezeASGCommand, UnfreezeASGCompleted}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.ResumeProcessesRequest
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class UnfreezeASGSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                              with Matchers with MockitoSugar with BeforeAndAfterAll {

  val mockedClient       = mock[AmazonAutoScaling]
  val successReq         = new ResumeProcessesRequest().withAutoScalingGroupName("success-req")
  val failReq            = new ResumeProcessesRequest().withAutoScalingGroupName("fail-req")
  val clientExceptionReq = new ResumeProcessesRequest().withAutoScalingGroupName("client-exception-req")

  //If we don't check Mock data response, we must have throw an exception if we didn't match the request.
  Mockito.doThrow(new IllegalArgumentException).when(mockedClient).resumeProcesses(org.mockito.Matchers.anyObject())
  Mockito.doNothing().when(mockedClient).resumeProcesses(successReq)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).resumeProcesses(failReq)
  Mockito.doThrow(new AmazonClientException("client-exception-req")).doNothing().when(mockedClient).resumeProcesses(clientExceptionReq)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A UnfreezeASG actor" should "unfreeze an ASG successfully" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, UnfreezeASG, system, TestActorFactory)

    probe.send(proxy, UnfreezeASGCommand("success-req"))
    probe.expectMsg(UnfreezeASGCompleted("success-req"))
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, UnfreezeASG, system, TestActorFactory)

    probe.send(proxy, UnfreezeASGCommand("fail-req"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, UnfreezeASG, system, TestActorFactory)

    probe.send(proxy, UnfreezeASGCommand("client-exception-req"))
    probe.expectMsg(UnfreezeASGCompleted("client-exception-req"))
  }

  val props = Props(new UnfreezeASG(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case UnfreezeASG => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }

}
