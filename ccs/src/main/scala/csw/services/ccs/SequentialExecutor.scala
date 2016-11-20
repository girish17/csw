package csw.services.ccs

import java.util.Optional

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import csw.services.ccs.CommandStatus._
import csw.util.config.Configurations.{SetupConfig, SetupConfigArg}

import scala.compat.java8.OptionConverters._

/**
 * TMT Source Code: 9/6/16.
 */

class SequentialExecutor(sca: SetupConfigArg, commandOriginator: Option[ActorRef]) extends Actor with ActorLogging {

  import SequentialExecutor._

  def receive = waitingReceive

  def waitingReceive: Receive = {
    case StartTheSequence(commandHandler) =>
      val configs = sca.configs
      // Start the first one
      context.become(executingReceive(configs, commandHandler, CommandStatus.CommandResults()))
      self ! SequentialExecute(configs.head)
    case x => log.error(s"TromboneCommandHandler:waitingReceive received an unknown message: $x")
  }

  def executingReceive(configsIn: Seq[SetupConfig], commandHandler: ActorRef, execResultsIn: CommandResults): Receive = {

    case SequentialExecute(sc: SetupConfig) =>
      log.debug(s"----->Executor Starting: $sc")
      commandHandler ! ExecuteOne(sc, Some(context.self))

    case StopCurrentCommand =>
      // StopCurrentCommand passes this on to the actor receiving command messages
      log.info(s"Received stop on: $sca")
      commandHandler ! StopCurrentCommand

    case cs @ CommandStatus.Completed =>
      // Save record of sequential successes
      val execResultsOut = execResultsIn :+ CommandResultPair(cs, configsIn.head)
      val configsOut = configsIn.tail
      if (configsOut.isEmpty) {
        // If there are no more in the sequence, return the completion for all and be done
        context.become(receive)
        // This returns the cumulative results to the original sender of the message to the sequenctial executor
        commandOriginator.foreach(_ ! CommandResult(sca.info.runId, AllCompleted, execResultsOut))
        context.stop(self)
      } else {
        // If there are more, start the next one and pass the completion status to the next execution
        context.become(executingReceive(configsOut, commandHandler, execResultsOut))
        self ! SequentialExecute(configsOut.head)
      }

    case cs @ NoLongerValid(issue) =>
      log.info(s"Validation Issue: $cs")
      // Save record of sequential successes
      context.become(receive)
      val execResultsOut = execResultsIn :+ CommandResultPair(cs, configsIn.head)
      commandOriginator.foreach(_ ! CommandResult(sca.info.runId, Incomplete, execResultsOut))
      context.stop(self)

    case cs: Error =>
      log.info(s"Received error: ${cs.message}")
      // Save record of sequential successes
      val execResultsOut = execResultsIn :+ CommandResultPair(cs, configsIn.head)
      context.become(receive)
      // This returns the cumulative results to the original sender of the message to the sequenctial executor
      commandOriginator.foreach(_ ! CommandResult(sca.info.runId, Incomplete, execResultsOut))
      context.stop(self)

    case cs: Invalid =>
      log.info(s"Received Invalid: ${cs.issue}")
      // Save record of sequential successes
      val execResultsOut = execResultsIn :+ CommandResultPair(cs, configsIn.head)
      context.become(receive)
      commandOriginator.foreach(_ ! CommandResult(sca.info.runId, Incomplete, execResultsOut))
      context.stop(self)

    case cs @ Cancelled =>
      log.info(s"Received Cancelled")
      // Save record of sequential successes
      val execResultsOut = execResultsIn :+ CommandResultPair(cs, configsIn.head)
      context.become(receive)
      commandOriginator.foreach(_ ! CommandResult(sca.info.runId, Incomplete, execResultsOut))
      context.stop(self)

    case x => log.error(s"SequentialExecutor:executingReceive received an unknown message: $x")

  }

}

object SequentialExecutor {

  def props(sca: SetupConfigArg, commandOriginator: Option[ActorRef]): Props = Props(classOf[SequentialExecutor], sca, commandOriginator)

  // Java API
  def props(sca: SetupConfigArg, commandOriginator: Optional[ActorRef]): Props = Props(classOf[SequentialExecutor], sca, commandOriginator.asScala)

  sealed trait SequenceExecutorMessages

  case class StartTheSequence(commandProcessor: ActorRef) extends SequenceExecutorMessages

  case class SequentialExecute(sc: SetupConfig) extends SequenceExecutorMessages

  case class ExecuteOne(sc: SetupConfig, commandOriginator: Option[ActorRef] = None) extends SequenceExecutorMessages

  case object StopCurrentCommand extends SequenceExecutorMessages

  case object CommandStart extends SequenceExecutorMessages

}

