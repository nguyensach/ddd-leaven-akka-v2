package ecommerce.headquarters.processes

import java.util.UUID

import com.github.nscala_time.time.Imports._
import ecommerce.headquarters.app.HeadquartersConfiguration
import ecommerce.headquarters.app.HeadquartersConfiguration.department
import ecommerce.headquarters.processes.OrderProcessManager.OrderStatus
import ecommerce.invoicing.{CancelInvoice, CreateInvoice, OrderBilled, OrderBillingFailed, PaymentExpired, _}
import ecommerce.sales.{CancelReservation, CloseReservation, Money, ReservationConfirmed, _}
import ecommerce.shipping.{CreateShipment, _}
import org.joda.time.DateTime._
import pl.newicom.dddd.actor.PassivationConfig
import pl.newicom.dddd.process._
import pl.newicom.dddd.saga.ProcessConfig
import pl.newicom.dddd.scheduling.schedulingOfficeId

object OrderProcessManager extends SagaSupport {

  val ProcessId = "order"

  sealed trait OrderStatus extends SagaState[OrderStatus] {
    def isNew = false
  }
  case object New extends OrderStatus {
    override def isNew: Boolean = true
  }
  case object WaitingForPayment  extends OrderStatus
  case object DeliveryInProgress extends OrderStatus
  case object Completed          extends OrderStatus
  case object Failed             extends OrderStatus

  implicit object OrderProcessConfig extends ProcessConfig[OrderProcessManager](ProcessId, department) {
    def correlationIdResolver = {
      case ReservationConfirmed(reservationId, _, _) => reservationId // orderId
      case OrderBilled(_, orderId, _, _) => orderId
      case OrderBillingFailed(_, orderId) => orderId
      case PaymentExpired(_, orderId) => orderId
    }
  }

}

import ecommerce.headquarters.processes.OrderProcessManager._

class OrderProcessManager(val pc: PassivationConfig) extends ProcessManager[OrderStatus] {

  val officeId = OrderProcessConfig

  def processCollaborators = List(
    schedulingOfficeId(HeadquartersConfiguration.department),
    InvoicingOfficeId, ReservationOfficeId, ShippingOfficeId
  )

  startWhen {

    case _: ReservationConfirmed => New

  } andThen {

    case New => {

      case ReservationConfirmed(reservationId, customerId, totalAmountOpt) =>

        WaitingForPayment {
          ⟶ (CreateInvoice(sagaId, reservationId, customerId, totalAmountOpt.getOrElse(Money()), now()))

          ⟵ (PaymentExpired(sagaId, reservationId)) in 3.minutes
        }

    }

    case WaitingForPayment => {

      case PaymentExpired(invoiceId, orderId) =>

        ⟶ (CancelInvoice(invoiceId, orderId))

      case OrderBilled(_, orderId, _, _) =>

        DeliveryInProgress {
          ⟶ (CloseReservation(orderId))

          ⟶ (CreateShipment(UUID.randomUUID().toString, orderId))
        }

      case OrderBillingFailed(_, orderId) =>

        Failed {
          ⟶ (CancelReservation(orderId))
        }
    }

  }

}
