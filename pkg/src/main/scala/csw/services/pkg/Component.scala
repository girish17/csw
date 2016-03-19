package csw.services.pkg

import akka.actor._
import csw.util.Components._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

/**
 * Represents a Component, such as an assembly, HCD (Hardware Control Daemon) or SC (Sequence Component).
 *
 * Each component has its own ActorSystem, LifecycleManager and name.
 */
object Component {

  sealed trait LocationServiceUsage
  case object DoNotRegister extends LocationServiceUsage
  case object RegisterOnly extends LocationServiceUsage
  case object RegisterAndTrackServices extends LocationServiceUsage

  sealed trait ComponentInfo {
    val componentName: String
    def componentType: ComponentType
    def componentClassName: String
    def locationServiceUsage: LocationServiceUsage
  }

  /**
   * Describes a component
   *
   * @param componentName name used to register the component with the location service
   * @param prefix the configuration prefix (part of configs that component should receive)
   * @param locationServiceUsage how the component plans to use the location service
   * @param rate the HCD's refresh rate
   */
  final case class HcdInfo(componentName: String,
                           prefix: String,
                           componentClassName: String,
                           locationServiceUsage: LocationServiceUsage,
                           registerAs: Set[ConnectionType],
                           rate: FiniteDuration) extends ComponentInfo {
    val componentType = HCD
  }

  /**
   * Describes an Assembly
   *
   * @param componentName name used to register the component with the location service
   * @param prefix the configuration prefix (part of configs that component should receive)
   * @param locationServiceUsage how the component plans to use the location service
   * @param connections a list of connections that includes componentIds and connection Types
   */
  final case class AssemblyInfo(componentName: String,
                                prefix: String,
                                componentClassName: String,
                                locationServiceUsage: LocationServiceUsage,
                                registerAs: Set[ConnectionType],
                                connections: Set[Connection]) extends ComponentInfo {
    val componentType = Assembly
  }

  final case class ContainerInfo(componentName: String,
                                 locationServiceUsage: LocationServiceUsage,
                                 registerAs: Set[ConnectionType],
                                 initialDelay: FiniteDuration = 1.second,
                                 creationDelay: FiniteDuration = 1.second,
                                 lifecycleDelay: FiniteDuration = 1.second,
                                 componentInfos: List[ComponentInfo]) extends ComponentInfo {
    val componentType = Container
    val componentClassName = "csw.services.pkg.ContainerComponent"
  }

  private def createHCD(context: ActorContext, cinfo: ComponentInfo): ActorRef = {
    // Form props for component
    val props = Props(Class.forName(cinfo.componentClassName), cinfo)

    context.actorOf(props, s"${cinfo.componentName}-${cinfo.componentType}")
  }

  private def createAssembly(context: ActorContext, cinfo: AssemblyInfo): ActorRef = {
    val props = Props(Class.forName(cinfo.componentClassName), cinfo)

    context.actorOf(props, s"${cinfo.componentName}-${cinfo.componentType}")
  }

  def create(context: ActorContext, componentInfo: ComponentInfo): ActorRef = componentInfo match {
    case hcd: HcdInfo ⇒
      createHCD(context, hcd)
    case ass: AssemblyInfo ⇒
      createAssembly(context, ass)
    case cont: ContainerInfo ⇒
      ContainerComponent.create(cont)
  }

}

trait Component extends Actor with ActorLogging {
  def supervisor = context.parent

  override def postStop: Unit = {
    log.info(s"Post Stop: !!")
  }
}

trait Assembly extends Component

trait Hcd extends Component

trait Container extends Component
