package mesosphere.marathon

import javax.inject.{ Inject, Named }

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusEmitter }
import mesosphere.marathon.event._
import mesosphere.marathon.tasks._
import mesosphere.util.state.{ FrameworkIdUtil, MesosLeaderInfo }
import org.apache.mesos.Protos._
import org.apache.mesos.{ Scheduler, SchedulerDriver }
import org.slf4j.{ LoggerFactory, MarkerFactory }

import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal

trait SchedulerCallbacks {
  def disconnected(): Unit
}

class MarathonScheduler @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    clock: Clock,
    offerProcessor: OfferProcessor,
    taskStatusEmitter: TaskStatusEmitter,
    frameworkIdUtil: FrameworkIdUtil,
    mesosLeaderInfo: MesosLeaderInfo,
    taskIdUtil: TaskIdUtil,
    system: ActorSystem,
    config: MarathonConf,
    schedulerCallbacks: SchedulerCallbacks) extends Scheduler {

  private[this] lazy val fatal = MarkerFactory.getMarker("FATAL")
  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  import mesosphere.util.ThreadPoolContext.context

  implicit val zkTimeout = config.zkTimeoutDuration

  override def registered(
    driver: SchedulerDriver,
    frameworkId: FrameworkID,
    master: MasterInfo): Unit = {
    log.info(s"Registered as ${frameworkId.getValue} to master '${master.getId}'")
    frameworkIdUtil.store(frameworkId)
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerRegisteredEvent(frameworkId.getValue, master.getHostname))
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    log.info("Re-registered to %s".format(master))
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerReregisteredEvent(master.getHostname))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]): Unit = {
    import scala.collection.JavaConverters._
    offers.asScala.foreach { offer =>
      val processFuture = offerProcessor.processOffer(offer)
      processFuture.onComplete {
        case scala.util.Success(_)           => log.debug(s"Finished processing offer '${offer.getId.getValue}'")
        case scala.util.Failure(NonFatal(e)) => log.error(s"while processing offer '${offer.getId.getValue}'", e)
      }
    }
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID): Unit = {
    log.info("Offer %s rescinded".format(offer))
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    taskStatusEmitter.publish(TaskStatusUpdate(timestamp = clock.now(), status.getTaskId, MarathonTaskStatus(status)))
  }

  override def frameworkMessage(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    message: Array[Byte]): Unit = {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
    eventBus.publish(MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message))
  }

  override def disconnected(driver: SchedulerDriver) {
    log.warn("Disconnected")

    eventBus.publish(SchedulerDisconnectedEvent())

    // Disconnection from the Mesos master has occurred.
    // Thus, call the scheduler callbacks.
    schedulerCallbacks.disconnected()
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID) {
    log.info(s"Lost slave $slave")
  }

  override def executorLost(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    p4: Int) {
    log.info(s"Lost executor $executor slave $p4")
  }

  override def error(driver: SchedulerDriver, message: String) {
    log.warn("Error: %s".format(message))

    // Currently, it's pretty hard to disambiguate this error from other causes of framework errors.
    // Watch MESOS-2522 which will add a reason field for framework errors to help with this.
    // For now the frameworkId is removed for all messages.
    val removeFrameworkId = true
    suicide(removeFrameworkId)
  }

  /**
    * Exits the JVM process, optionally deleting Marathon's FrameworkID
    * from the backing persistence store.
    *
    * If `removeFrameworkId` is set, the next Marathon process elected
    * leader will fail to find a stored FrameworkID and invoke `register`
    * instead of `reregister`.  This is important because on certain kinds
    * of framework errors (such as exceeding the framework failover timeout),
    * the scheduler may never re-register with the saved FrameworkID until
    * the leading Mesos master process is killed.
    */
  private def suicide(removeFrameworkId: Boolean): Unit = {
    log.error(fatal, s"Committing suicide!")

    if (removeFrameworkId) Await.ready(frameworkIdUtil.expunge(), config.zkTimeoutDuration)

    // Asynchronously call sys.exit() to avoid deadlock due to the JVM shutdown hooks
    // scalastyle:off magic.number
    Future(sys.exit(9)).onFailure {
      case NonFatal(t) => log.error(fatal, "Exception while committing suicide", t)
    }
    // scalastyle:on
  }
}
