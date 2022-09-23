package mylib

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.amba3.apb.{Apb3SlaveFactory, Apb3}

case class Apb3Timer() extends Component{
  val io = new Bundle{
    val apb = slave(Apb3(addressWidth = 8,dataWidth = 32))
    val fullA = out Bool
    val fullB = out Bool
    val external = new Bundle{
      val tick  = in Bool
      val clear = in Bool
    }
  }

  val clockDivider = new Area{
    val counter = Reg(UInt(4 bits)) init(0)
    counter := counter + 1
    val full = counter === 0xF
  }

  val apbCtrl = Apb3SlaveFactory(io.apb)
  val timerA  = Timer(width = 16)
  val bridgeA = timerA.driveFrom(apbCtrl,0x00)(
    ticks  = List(True,clockDivider.full,io.external.tick),
    clears = List(timerA.io.full,io.external.clear)
  )

  val timerB  = Timer(width = 8)
  val bridgeB = timerB.driveFrom(apbCtrl,0x10)(
    ticks  = List(True,clockDivider.full,io.external.tick),
    clears = List(timerB.io.full,io.external.clear)
  )

  io.fullA := timerA.io.full
  io.fullB := timerB.io.full

  assert(!clockDivider.full,"test assert", FAILURE)
}


case class Timer(width : Int) extends Component {
  val io = new Bundle {
    val tick = in Bool
    val clear = in Bool
    val limit = in UInt (width bits)

    val full  = out Bool
    val value = out UInt (width bits)
  }

  val counter = Reg(UInt(width bits))
  when(io.tick && !io.full) {
    counter := counter + 1
  }
  when(io.clear) {
    counter := 0
  }

  io.full := counter === io.limit
  io.value := counter

  def driveFrom(busCtrl : BusSlaveFactory,baseAddress : BigInt)(ticks : Seq[Bool],clears : Seq[Bool]) = new Area {
    //Address 0 => clear/tick masks + bus
    val ticksEnable  = busCtrl.createReadAndWrite(Bits(ticks.length bits) ,baseAddress + 0,0)  init(0)
    val clearsEnable = busCtrl.createReadAndWrite(Bits(clears.length bits),baseAddress + 0,16) init(0)
    val busClearing  = False

    io.clear := (clearsEnable & clears.asBits).orR | busClearing
    io.tick  := (ticksEnable  & ticks.asBits ).orR

    //Address 4 => read/write limit (+ auto clear)
    busCtrl.driveAndRead(io.limit,baseAddress + 4)
    busClearing.setWhen(busCtrl.isWriting(baseAddress + 4))

    //Address 8 => read timer value / write => clear timer value
    busCtrl.read(io.value,baseAddress + 8)
    busClearing.setWhen(busCtrl.isWriting(baseAddress + 8))
  }
}
