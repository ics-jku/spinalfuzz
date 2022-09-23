package mylib

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.sim._
import spinal.core.fuzz._
import spinal.sim.fuzz._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba3.ahblite._
import spinal.lib.com.spi._


import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._

object Apb3SpiSlaveVerilog {

  val spiConfig = SpiSlaveCtrlMemoryMappedConfig(
    ctrlGenerics = SpiSlaveCtrlGenerics(
      dataWidth = 8
    ),
    rxFifoDepth = 32,
    txFifoDepth = 32
  )


  def main(args: Array[String]) {
    SpinalVerilog(new Apb3SpiSlaveCtrl(spiConfig))
  }

}


object Apb3SpiSlaveFuzz {
  val spiConfig = SpiSlaveCtrlMemoryMappedConfig(
    ctrlGenerics = SpiSlaveCtrlGenerics(
      dataWidth = 8
    ),
    rxFifoDepth = 32,
    txFifoDepth = 32
  )

  def main(args:Array[String]) {
    FuzzConfig
      .fuzzTime(3600)
      .withInputCombined
      .withLlvm
      .withSysChange
      .withLineCovOnly
      .compile(new Apb3SpiSlaveCtrl(spiConfig))
  }
}


object Apb3SpiSlaveSim {
  val spiConfig = SpiSlaveCtrlMemoryMappedConfig(
    ctrlGenerics = SpiSlaveCtrlGenerics(
      dataWidth = 8
    ),
    rxFifoDepth = 32,
    txFifoDepth = 32
  )

  def main(args:Array[String]) {
    SimConfig
      .withCoverage
      //.withWave
      .compile(new Apb3SpiSlaveCtrl(spiConfig))
      .doSim { dut =>

        dut.clockDomain.forkStimulus(period = 10)

        val randMax1 : Int = scala.math.pow(2,1).toInt
        val randMax8 : Int = scala.math.pow(2,8).toInt
        val randMax32 : Int = scala.math.pow(2,32).toInt

        val deadline = 3600.seconds.fromNow

        var randApbPaddr   : Int     = 0
        var randApbPsel    : Int     = 0
        var randApbPenable : Boolean = false
        var randApbPwrite  : Boolean = false
        var randApbPwdata  : Int     = 0
        var randSpiSclk    : Boolean = false
        var randSpiMosi    : Boolean = false
        var randSpiSs      : Boolean = false

        var randWait : Int = 0


        sleep(10)
        dut.clockDomain.assertReset()
        sleep(10)
        dut.clockDomain.deassertReset()

        println("Reset done, start random assignments")

        while (deadline.hasTimeLeft) {

          dut.clockDomain.waitRisingEdge()

          randApbPaddr   = Random.nextInt(randMax8)
          randApbPsel    = Random.nextInt(randMax1)
          randApbPenable = Random.nextBoolean()
          randApbPwrite  = Random.nextBoolean()
          randApbPwdata  = Random.nextInt(randMax32)
          randSpiSclk    = Random.nextBoolean()
          randSpiMosi    = Random.nextBoolean()
          randSpiSs      = Random.nextBoolean()


          randWait    = Random.nextInt(256)

          dut.io.apb.PADDR   #= randApbPaddr
          dut.io.apb.PSEL    #= randApbPsel
          dut.io.apb.PENABLE #= randApbPenable
          dut.io.apb.PWRITE  #= randApbPwrite
          dut.io.apb.PWDATA  #= randApbPwdata
          dut.io.spi.sclk    #= randSpiSclk
          dut.io.spi.mosi    #= randSpiMosi
          dut.io.spi.ss      #= randSpiSs

        }
      }
  }
}
