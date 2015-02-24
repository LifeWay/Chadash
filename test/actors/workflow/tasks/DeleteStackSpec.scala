package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.DeleteStack.{DeleteStackCommand, StackDeleteRequested}
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class DeleteStackSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                              with Matchers with MockitoSugar {

  val mockedClient       = mock[AmazonCloudFormation]
  val successReq         = new DeleteStackRequest().withStackName("delete-success")
  val failReq            = new DeleteStackRequest().withStackName("fail-stack")
  val clientExceptionReq = new DeleteStackRequest().withStackName("client-exception-stack")

  //If we don't check Mock data response, we must have throw an exception if we didn't match the request.
  Mockito.doThrow(new IllegalArgumentException).when(mockedClient).deleteStack(org.mockito.Matchers.anyObject())
  Mockito.doNothing().when(mockedClient).deleteStack(successReq)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).deleteStack(failReq)
  Mockito.doThrow(new AmazonClientException("connection problems")).doNothing().when(mockedClient).deleteStack(clientExceptionReq)

  "A DeleteStack actor" should "request to delete the stack and return a response" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, DeleteStack, system, TestActorFactory)

    probe.send(proxy, DeleteStackCommand("delete-success"))
    probe.expectMsg(StackDeleteRequested)
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, DeleteStack, system, TestActorFactory)

    probe.send(proxy, DeleteStackCommand("fail-stack"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, DeleteStack, system, TestActorFactory)

    probe.send(proxy, DeleteStackCommand("client-exception-stack"))
    probe.expectMsg(StackDeleteRequested)
  }

  val props = Props(new DeleteStack(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case DeleteStack => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }
}
