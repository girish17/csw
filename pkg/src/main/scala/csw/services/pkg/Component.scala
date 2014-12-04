package csw.services.pkg

import akka.actor._
import csw.services.ls.LocationService.RegInfo

/**
 * Represents a Component, such as an assembly, HCD (Hardware Control Daemon) or SC (Sequence Component).
 *
 * Each component has its own ActorSystem, LifecycleManager and name.
 */
object Component {

  /**
   * Describes a component
   * @param props the props used to create the component actor
   * @param regInfo used to register the component with the location service
   * @param system the component's private actor system
   * @param lifecycleManager the component's lifecycle manager
   */
  case class ComponentInfo(props: Props, regInfo: RegInfo, system: ActorSystem, lifecycleManager: ActorRef)

  /**
   * Creates a component actor with a new ActorSystem and LifecycleManager
   * using the given props and name
   * @param props used to create the actor
   * @param regInfo used to register the component with the location service
   * @return an object describing the component
   */
  def create(props: Props, regInfo: RegInfo): ComponentInfo = {
    val name = regInfo.serviceId.name
    val system = ActorSystem(s"$name-system")
    val lifecycleManager = system.actorOf(LifecycleManager.props(props, regInfo), s"$name-lifecycle-manager")
    ComponentInfo(props, regInfo, system, lifecycleManager)
  }
}

trait Component

/**
 * An assembly is a component and may optionally also extend CommandServiceActor (or AssemblyCommandServiceActor)
 */
trait Assembly extends Component {
  this: Actor with ActorLogging ⇒
}

/**
 * An HCD is a component and may optionally also extend CommandServiceActor
 */
trait Hcd extends Component {
  this: Actor with ActorLogging ⇒
}
