package spinal.lib.com.usb.phy

import spinal.core._
import spinal.lib._


object UsbHubLsFs{
  object RxKind extends SpinalEnum{
    val NONE, RESUME, PACKET = newElement()
  }

  case class CtrlPort() extends Bundle with IMasterSlave {
    val disable = Event
    val removable = Bool()
    val power = Bool()
    val reset = Event
    val suspend = Event
    val resume = Event

    val connected = Bool()
    val overcurrent = Bool()
    val remoteResume = Bool()
    val lowSpeed = Bool()

    override def asMaster(): Unit = {
      out(removable, power)
      master(reset, suspend, resume, disable)
      in(connected, overcurrent, lowSpeed, remoteResume)
    }
  }


  case class CtrlRx() extends Bundle with IMasterSlave {
    val valid = Bool()
    val error = Bool()
    val active = Bool()
    val data = Bits(8 bits)

    override def asMaster(): Unit = out(this)
  }

  case class Ctrl(portCount : Int) extends Bundle with IMasterSlave{
    val lowSpeed = Bool()
    val overcurrent = Bool()
    val tx = Stream(Fragment(Bits(8 bits)))
    val rx = CtrlRx()
    val remoteWakupEnable = Bool()

    val ports = Vec(CtrlPort(), portCount)

    override def asMaster(): Unit = {
      in(overcurrent)
      out(lowSpeed, remoteWakupEnable)
      master(tx)
      slave(rx)
      ports.foreach(master(_))
    }
  }
}


