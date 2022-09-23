package mylib

import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.core.fuzz._
import spinal.sim.fuzz._

import scala.collection.mutable
import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._


// testing: change the SpinalConfig to
//          * get debug information to the console
object SpinalConfigDebug extends SpinalConfig(verbose = true, keepAll = true)


object GCDVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(new GCD(8))
  }
}

object GCDFuzz {

  var data_width : Int = 32
  def main(args: Array[String]) {
    FuzzConfig
//      .withConfig(SpinalConfigDebug)
      .fuzzTime(3600) // in seconds
      .withInputCombined
      .withSleepStmnt
      .withLlvm
      .withSysChange
      .withLineCovOnly
//      .withCrashes
      .compile(new GCD(data_width))
  }
}

object GCDSim {

  var dataWidth : Int = 16
  def main(args: Array[String]) {
    SimConfig
//      .withConfig(SpinalConfigDebug)
      .withCoverage
      .withWave
      .compile {
        val dut = new GCD(dataWidth)
        dut.clockDomain.reset.simPublic()
        dut
      }
      .doSim
//      .doSim(name="SimRand")
    { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      val randMaxData : Int = scala.math.pow(2,dataWidth).toInt

      val deadline = 3600.seconds.fromNow

      var randA : Int = 0
      var randB : Int = 0
      var randEn : Boolean = false

      var randWait  : Int = 0

      var resSw : Int = 0
      var resHw : Int = 0

      sleep(10)
      //DoReset(dut.clockDomain.reset, 2, HIGH)

      dut.clockDomain.assertReset()
      sleep(10)
      dut.clockDomain.deassertReset()

      var iter = 0

      while (deadline.hasTimeLeft) {

        //println(iter)

        if (iter == 5) {
          //println(iter)
//          println("-- reset --")
          dut.clockDomain.assertReset()
//          dut.clockDomain.waitSampling(5)
          sleep(10)
          //println(dut.clockDomain.resetSim.toBoolean)
          //dut.clockDomain.waitSampling(5)
          dut.clockDomain.deassertReset()
          iter = 0
        }

        iter = iter + 1

        dut.clockDomain.waitRisingEdge()

        randA = Random.nextInt(randMaxData)
        randB = Random.nextInt(randMaxData)
        randEn = Random.nextBoolean()

        randWait = Random.nextInt(50)

        dut.io.en #= randEn
        dut.io.a #= randA
        dut.io.b #= randB

        dut.clockDomain.waitSampling(randWait)
//        dut.clockDomain.waitRisingEdge()
//        randEn = Random.nextBoolean()
//        dut.io.en #= false //randEn

//        if (randEn) {
//        if (true) {
//          dut.clockDomain.waitRisingEdgeWhere(dut.io.rdy.toBoolean)
//          resHw = dut.io.result.toInt

//          resSw = gcd(randA, randB)

          //assert(resHw == resSw)
//          if (dut.io.result.toInt != resSw)
//            println (s"A: ${dut.io.a.toInt}, B: ${dut.io.b.toInt}, result: ${dut.io.result.toInt}, swRes: ${resSw}, pass: ${dut.io.result.toInt == resSw}")
//        }
      }


    }

  }

  def gcdSw(a: Int, b: Int, en: Boolean): Int = {
    if (en) {
      gcd(a,b)
    }
    else {
      0
    }
  }

  // gcd calculation using scala
  def gcd(a: Int, b: Int): Int =
    if (b == 0) a else gcd(b, a % b)
}
