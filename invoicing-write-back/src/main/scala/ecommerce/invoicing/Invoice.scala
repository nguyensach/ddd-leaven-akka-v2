package ecommerce.invoicing

import java.util.UUID

import akka.actor.ActorRef
import ecommerce.invoicing.Invoice._
import ecommerce.sales._
import org.joda.time.DateTime
import pl.newicom.dddd.actor.PassivationConfig
import pl.newicom.dddd.aggregate
import pl.newicom.dddd.aggregate.{AggregateRoot, AggregateState, EntityId}
import pl.newicom.dddd.eventhandling.EventPublisher
import pl.newicom.dddd.messaging.event.DomainEventMessage

import scala.concurrent.duration.DurationInt

object Invoice {

  //
  // Commands
  //
  sealed trait Command extends aggregate.Command {
    def invoiceId: EntityId
    override def aggregateId = invoiceId
  }

  case class CreateInvoice(invoiceId: EntityId, orderId: EntityId, customerId: EntityId, totalAmount: Money, createEpoch: DateTime) extends Command
  case class ReceivePayment(invoiceId: EntityId, amount: Money, paymentId: EntityId) extends Command

  //
  // Events
  //
  case class InvoiceCreated(invoiceId: EntityId, orderId: EntityId, customerId: EntityId, totalAmount: Money, createEpoch: DateTime)
  case class PaymentReceived(invoiceId: EntityId, amount: Money, paymentId: EntityId)

  def persistenceId(aggregateId: EntityId) = "Invoice-" + aggregateId

  case class State(amountPaid: Option[Money]) extends AggregateState {
    override def apply = {
      case PaymentReceived(_, amount, _) =>
        copy(amountPaid = Some(amountPaid.getOrElse(Money()) + amount))
    }
  }

}

abstract class Invoice(override val pc: PassivationConfig) extends AggregateRoot[State] {
  this: EventPublisher =>

  override def persistenceId = Invoice.persistenceId(id)

  override val factory: AggregateRootFactory = {
    case InvoiceCreated(_, _, _, _, _) =>
      State(None)
  }

  override def handleCommand: Receive = {
    case CreateInvoice(invoiceId, orderId, customerId, totalAmount, createEpoch) =>
      if (initialized) {
        throw new RuntimeException(s"Invoice $invoiceId already exists")
      } else {
        raise(InvoiceCreated(invoiceId, orderId, customerId, totalAmount, createEpoch))
      }

    case ReceivePayment(invoiceId, amount, paymentId) =>
      if (initialized) {
        raise(PaymentReceived(invoiceId, amount, paymentId))
      } else {
        throw new RuntimeException(s"Unknown invoice")
      }
  }

  override def handle(senderRef: ActorRef, em: DomainEventMessage): Unit = em.event match {
    // simulate payment receipt (schedule ReceivePayment, 10s)
    case InvoiceCreated(invoiceId, _, _, totalAmount, _) =>
      val receivePayment = ReceivePayment(invoiceId, totalAmount, paymentId = UUID.randomUUID().toString)
      import context.dispatcher
      context.system.scheduler.scheduleOnce(10.seconds, self, receivePayment)
    case _ => ()

    super.handle(senderRef, em)
  }
}
