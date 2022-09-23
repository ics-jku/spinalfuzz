package mylib

import spinal.core._
import spinal.core.sim._
import spinal.sim._
import spinal.core.fuzz._
import spinal.sim.fuzz._

import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.com.uart.{UartStopType,UartParityType}

import spinal.lib.bus.amba3.apb.{Apb3SlaveFactory, Apb3}

import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._

object UartCtrlFuzz {

  def main(args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600) // in seconds
      .withInputCombined
      //.withSleepStmnt
      .withLlvm
      .withSysChange
      .withLineCovOnly
//      .withCrashes
      .compile(new UartCtrl)
  }
}

object UartCtrlSim {
  def main(args: Array[String]) {
    SimConfig
      .withCoverage
      .withWave
      .compile(new UartCtrl)
      .doSim { dut =>

        dut.clockDomain.forkStimulus(period = 10)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitRisingEdge()
        dut.clockDomain.deassertReset()

        val uartParityType = Seq(UartParityType.NONE, UartParityType.EVEN, UartParityType.ODD)
        val uartStopType = Seq(UartStopType.ONE, UartStopType.TWO)

        val randMaxStop : Int = uartStopType.length
        val randMaxParity : Int = uartParityType.length
        val randMax3 : Int = scala.math.pow(2,3).toInt
        val randMax8 : Int = scala.math.pow(2,8).toInt
        val randMax20 : Int = scala.math.pow(2,20).toInt

        val deadline = 3600.seconds.fromNow

        var randDataLength   : Int = 0
        var randFrameStop    : Int = 0
        var randFrameParity  : Int = 0
        var randClockDiv     : Int = 0
        var randWriteValid   : Boolean = false
        var randWritePayload : Int = 0
        var randReadReady    : Boolean = false
        var randUartRxd      : Boolean = false
        var randWriteBreak   : Boolean = false

        var randWait : Int = 0

        dut.clockDomain.waitRisingEdge()

        while (deadline.hasTimeLeft) {
           
          randDataLength   = Random.nextInt(randMax3)
          randFrameStop    = Random.nextInt(randMaxStop)
          randFrameParity  = Random.nextInt(randMaxParity)
          randClockDiv     = Random.nextInt(randMax20)
          randWriteValid   = Random.nextBoolean()
          randWritePayload = Random.nextInt(randMax8)
          randReadReady    = Random.nextBoolean()
          randUartRxd      = Random.nextBoolean()
          randWriteBreak   = Random.nextBoolean()


          randWait = Random.nextInt(256)

          dut.io.config.frame.dataLength #= randDataLength
          dut.io.config.frame.stop       #= uartStopType(randFrameStop)
          dut.io.config.frame.parity     #= uartParityType(randFrameParity)
          dut.io.config.clockDivider     #= randClockDiv
          dut.io.write.valid             #= randWriteValid
          dut.io.write.payload           #= randWritePayload
          dut.io.read.ready              #= randReadReady
          dut.io.uart.rxd                #= randUartRxd
          dut.io.writeBreak              #= randWriteBreak

          dut.clockDomain.waitRisingEdge()
          //dut.clockDomain.waitSampling(randWait)
        }
      }
  }
}

object UartCtrlVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(new UartCtrl)
  }
}
