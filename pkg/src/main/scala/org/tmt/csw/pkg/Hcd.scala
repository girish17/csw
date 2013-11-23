package org.tmt.csw.pkg

import org.tmt.csw.cmd.akka.{ConfigActor, CommandServiceActor}
import akka.actor.ActorRef

/**
 * Represents an HCD (Hardware Control Daemon) component.
 */
trait Hcd extends Component with CommandServiceActor  {

  // Receive actor messages.
  def receiveHcdMessages: Receive = receiveComponentMessages orElse receiveCommands

  override def terminated(actorRef: ActorRef): Unit = log.info(s"Actor $actorRef terminated")
}