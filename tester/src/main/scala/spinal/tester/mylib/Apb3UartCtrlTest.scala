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


object Apb3UartVerilog {

  val uartCtrlConfig = UartCtrlMemoryMappedConfig(
    uartCtrlConfig = UartCtrlGenerics(
      dataWidthMax      = 8,
      clockDividerWidth = 20,
      preSamplingSize   = 1,
      samplingSize      = 5,
      postSamplingSize  = 2
    ),
    txFifoDepth = 16,
    rxFifoDepth = 16
  )

  def main(args: Array[String]) {
    SpinalVerilog(new Apb3UartCtrl(uartCtrlConfig))
  }
}

object Apb3UartFuzz {

  val uartCtrlConfig = UartCtrlMemoryMappedConfig(
    uartCtrlConfig = UartCtrlGenerics(
      dataWidthMax      = 8,
      clockDividerWidth = 20,
      preSamplingSize   = 1,
      samplingSize      = 5,
      postSamplingSize  = 2
    ),
    txFifoDepth = 16,
    rxFifoDepth = 16
  )

  def main(args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600) // in seconds
      .withInputCombined
      .withWave
      //.withSleepStmnt
      .withLlvm
      .withSysChange
      .withLineCovOnly
      .withCrashes
      .compile(new Apb3UartCtrl(uartCtrlConfig))
  }
}

object Apb3UartSim {

  val uartCtrlConfig = UartCtrlMemoryMappedConfig(
    uartCtrlConfig = UartCtrlGenerics(
      dataWidthMax      = 8,
      clockDividerWidth = 20,
      preSamplingSize   = 1,
      samplingSize      = 5,
      postSamplingSize  = 2
    ),
    txFifoDepth = 16,
    rxFifoDepth = 16
  )

  def main(args: Array[String]) {
    SimConfig
      .withCoverage
      .withWave
      .compile(new Apb3UartCtrl(uartCtrlConfig))
      .doSim { dut =>

        dut.clockDomain.forkStimulus(period = 10)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitRisingEdge()
        dut.clockDomain.deassertReset()

        val randMax1 : Int = scala.math.pow(2,1).toInt
        val randMax5 : Int = scala.math.pow(2,5).toInt
        val randMax32 : Int = scala.math.pow(2,32).toInt

        val deadline = 3600.seconds.fromNow



        var randApb_PADDR   : Int = 0 // [4:0]
        var randApb_PSEL    : Int = 0 // [0:0]
        var randApb_PENABLE : Boolean = false
        var randApb_PWRITE  : Boolean = false
        var randApb_PWDATA  : Int = 0 // [31:0]
        var randUart_rxd    : Boolean = false

        var randWait : Int = 0

        dut.clockDomain.waitRisingEdge()

        while (deadline.hasTimeLeft) {


          randApb_PADDR   = Random.nextInt(randMax5)
          randApb_PSEL    = Random.nextInt(randMax1)
          randApb_PENABLE = Random.nextBoolean()
          randApb_PWRITE  = Random.nextBoolean()
          randApb_PWDATA  = Random.nextInt(randMax32)
          randUart_rxd    = Random.nextBoolean()

          randWait = Random.nextInt(256)


          dut.io.apb.PADDR   #= randApb_PADDR
          dut.io.apb.PSEL    #= randApb_PSEL
          dut.io.apb.PENABLE #= randApb_PENABLE
          dut.io.apb.PWRITE  #= randApb_PWRITE
          dut.io.apb.PWDATA  #= randApb_PWDATA
          dut.io.uart.rxd    #= randUart_rxd

          dut.clockDomain.waitRisingEdge()
          //dut.clockDomain.waitSampling(randWait)
        }
      }
  }

}
