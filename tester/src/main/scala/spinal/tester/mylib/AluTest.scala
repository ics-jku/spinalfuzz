package mylib

import spinal.core._
import spinal.core.sim._
import spinal.sim._

import spinal.core.fuzz._
import spinal.sim.fuzz._

import spinal.lib.cpu.riscv.impl._
import spinal.lib.cpu.riscv.impl.Utils._

import scala.util.Random
import scala.concurrent.duration._

object AluVerilog{
  def main(args: Array[String]) {
    SpinalVerilog(new AluInst().setDefinitionName("TopLevel"))
  }
}


object AluFuzz {
  def main(args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600)
      .withInputCombined
      .withLlvm
      .withSysChange
      .withLineCovOnly
//      .withCrashes
      .compile(new AluInst())
  }
}

object AluSim {

  def main(args: Array[String]) {
    SimConfig
      .withCoverage
      .withWave
      .compile(new Alu())
      .doSim(name="testingAlu")
    { dut =>

      val aluOp = Seq(ALU.ADD, ALU.SLL1, ALU.SLT, ALU.SLTU, ALU.XOR, ALU.SRL, ALU.OR, ALU.AND, ALU.SUB, ALU.COPY, ALU.SRA)

      val randMaxFunc : Int = aluOp.length
      val randMaxSrc  : Int = scala.math.pow(2,32).toInt

      var randFunc : Int = 0
      var randDoSub : Boolean = false
      var randSrc0 : Int = 0
      var randSrc1 : Int = 0

      var iter = 0
      val deadline = 3600.seconds.fromNow

      while (deadline.hasTimeLeft) {

        sleep(2)

        randFunc = Random.nextInt(randMaxFunc)
        randDoSub = Random.nextBoolean()
        randSrc0 = Random.nextInt(randMaxSrc)
        randSrc1 = Random.nextInt(randMaxSrc)

        dut.io.func #= aluOp(randFunc)
        dut.io.doSub #= randDoSub
        dut.io.src0 #= randSrc0
        dut.io.src1 #= randSrc1
      }

    }
  }
}
